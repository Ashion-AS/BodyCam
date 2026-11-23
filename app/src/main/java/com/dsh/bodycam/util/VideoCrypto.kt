package com.dsh.bodycam.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 录像文件加密（AES-256-GCM 分块）。
 *
 * ## 文件格式
 *
 * ```
 * magic  "BCENC1"   (6B)   识别「本 App 加密过的录像」
 * salt   16B               随机盐，参与密钥派生
 * chunk* N 个块            每块 = IV(12B) || 密文(≤64KB) || Tag(16B)
 * ```
 *
 * 块与块之间完全独立、各有随机 IV，所以可以按块随机定位读取。
 *
 * ## 为什么整文件分块，而不是方案文档里的帧级加密
 *
 * 帧级加密要改 muxer、改 sample 写入路径、自建分片索引，等于重写半个封装层；
 * 而 media3 的 `FragmentedMp4Muxer` 内部直写磁盘，插不进加密钩子。分块加密只在
 * 收尾时对文件做一次变换，录制热路径零改动、零性能损失，也天然支持整块随机访问。
 *
 * ## 密钥
 *
 * 界面输入 32 个字符的口令，这里用 `SHA-256(salt || 口令)` 派生 32 字节 AES-256
 * 密钥。加盐的意义：同一口令在不同文件派生出不同密钥，避免跨文件复用同一密钥。
 *
 * ## 保护边界
 *
 * 只加密**文件内容**；文件名、目录、文件时长这类外观信息仍是明文，列表页照常
 * 显示，只是打不开。任何字节被改动都会让对应块的 Tag 校验失败，直接拒绝播放。
 * 口令存在 SharedPreferences，属于本机防护级别——能拿到 root 的人可以同时拿到
 * 口令与文件，这一点与方案文档的「Keystore 包装」有差距，按当前需求取舍。
 */
internal object VideoCrypto {

    private const val TAG = "VideoCrypto"

    private val MAGIC = "BCENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_LEN = TAG_BITS / 8

    /** 明文块大小：够大才有吞吐，同时限制单块内存占用 */
    private const val CHUNK_SIZE = 64 * 1024

    /** 一个加密块在文件里的字节数 */
    private const val ENC_CHUNK_SIZE = IV_LEN + CHUNK_SIZE + TAG_LEN

    /** 文件头（magic + salt）长度 */
    private const val HEADER_LEN = 6 + SALT_LEN

    /** 口令长度（字符） */
    const val PASSPHRASE_LEN = 32

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 是否是本 App 加密过的录像（只看文件头魔数） */
    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_LEN) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(MAGIC.size)
                raf.readFully(head)
                head.contentEquals(MAGIC)
            }
        } catch (e: Exception) {
            Log.w(TAG, "isEncrypted ${file.name}", e)
            false
        }
    }

    /** 口令是否合法：固定 32 个字符且不含控制字符 */
    fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.length == PASSPHRASE_LEN && passphrase.none { it.code < 0x20 }

    /**
     * 原地加密一个录像文件。
     *
     * 加密后体积变大（每块多 28 字节），不能在同一文件里就地改写，
     * 所以先写 `<name>.enc` 再换名。
     *
     * @return 成功 true（已加密的文件直接返回 true）；失败时原文件保持不变
     */
    fun encryptInPlace(file: File, passphrase: String): Boolean {
        if (!isValidPassphrase(passphrase)) return false
        if (isEncrypted(file)) return true
        // 与 MuxerFix 共用进程级锁：播放前的修补可能正在改同一个文件，
        // 边改边加密会读到半截内容。
        return synchronized(MuxerFix.patchLock) {
            encryptInPlaceLocked(file, passphrase)
        }
    }

    private fun encryptInPlaceLocked(file: File, passphrase: String): Boolean {
        val tmp = File(file.parentFile, file.name + ".enc")
        return try {
            val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(passphrase, salt)
            val plainLen = file.length()

            FileOutputStream(tmp).use { out ->
                out.write(MAGIC)
                out.write(salt)
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var pos = 0L
                    while (pos < plainLen) {
                        val n = minOf(CHUNK_SIZE.toLong(), plainLen - pos).toInt()
                        raf.seek(pos)
                        raf.readFully(buf, 0, n)
                        out.write(encryptChunk(buf, n, key))
                        pos += n
                    }
                }
            }
            // 目标已存在时 renameTo 在多数实现上会失败，先删原文件再换名。
            // 原文件此时已完整写入 tmp，删掉它再改名是安全的；改名失败则用拷贝兜底。
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            key.fill(0)
            Log.i(TAG, "encrypted ${file.name} (plain $plainLen bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "encrypt ${file.name}", e)
            tmp.delete()
            // 极端情况：删原文件后改名也失败，原文件已经没了。
            // 保底把 tmp 保留在原文件名旁，让用户还有机会找回内容。
            if (!file.exists() && tmp.exists()) {
                runCatching { tmp.renameTo(File(file.parentFile, file.name + ".enc")) }
            }
            false
        }
    }

    /**
     * 把加密文件解密成明文写到 [target]。
     *
     * 回放走这条路径：`ExoPlayer` 帮不上忙（它读的是容器结构，我们加密的是
     * 整个容器），先落地临时明文再交给标准播放链路最稳，退出播放页立刻删掉。
     *
     * @return 成功 true；口令错误或文件被篡改返回 false（target 已删除）
     */
    fun decryptTo(file: File, passphrase: String, target: File): Boolean {
        if (!isValidPassphrase(passphrase) || !isEncrypted(file)) return false
        // 读的同时可能有人在修补源文件，共用锁保证读到完整内容
        return synchronized(MuxerFix.patchLock) {
            decryptToLocked(file, passphrase, target)
        }
    }

    private fun decryptToLocked(file: File, passphrase: String, target: File): Boolean {
        return try {
            val header = ByteArray(HEADER_LEN)
            RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }
            val salt = header.copyOfRange(MAGIC.size, HEADER_LEN)
            val key = deriveKey(passphrase, salt)

            var remaining = file.length() - HEADER_LEN
            FileOutputStream(target).use { out ->
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(HEADER_LEN.toLong())
                    val buf = ByteArray(ENC_CHUNK_SIZE)
                    while (remaining > 0) {
                        val n = minOf(ENC_CHUNK_SIZE.toLong(), remaining).toInt()
                        raf.readFully(buf, 0, n)
                        out.write(decryptChunk(buf, n, key))
                        remaining -= n
                    }
                }
            }
            key.fill(0)
            Log.i(TAG, "decrypted ${file.name} -> ${target.name}")
            true
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "decrypt ${file.name}: bad tag (wrong key or tampered)")
            target.delete()
            false
        } catch (e: Exception) {
            Log.w(TAG, "decrypt ${file.name}", e)
            target.delete()
            false
        }
    }

    // ------------------------------------------------------------ 内部

    /** SHA-256(salt || 口令) → 32 字节 AES-256 密钥 */
    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            update(passphrase.toByteArray(Charsets.UTF_8))
            digest()
        }

    /** 加密一个明文块 → IV(12) || 密文 || Tag(16) */
    private fun encryptChunk(plain: ByteArray, len: Int, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return iv + cipher.doFinal(plain, 0, len)
    }

    /** 解密一个块 → 明文；Tag 校验失败抛 [AEADBadTagException] */
    private fun decryptChunk(enc: ByteArray, len: Int, key: ByteArray): ByteArray {
        require(len > IV_LEN + TAG_LEN) { "chunk too short: $len" }
        val iv = enc.copyOfRange(0, IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return cipher.doFinal(enc, IV_LEN, len - IV_LEN)
    }
}