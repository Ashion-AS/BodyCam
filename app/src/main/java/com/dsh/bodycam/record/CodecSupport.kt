package com.dsh.bodycam.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.math.max

/**
 * [SegmentRecorder] 与 [PreRecordBuffer] 共用的编码参数与编解码小工具。
 *
 * 两者原本各抄了一份 AAC 配置、AudioRecord 构造、feedAudio/queueEos/copyOut
 * 和一堆魔数，改一处就得改两处。集中到这里后只有一份事实来源。
 *
 * 注意：预录与录像必须用**完全一致**的音频参数，否则交接的 AAC 帧无法写进
 * 同一条音轨。
 */
internal const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
internal const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
internal const val SAMPLE_RATE = 48_000
internal const val CHANNELS = 1
internal const val AUDIO_BITRATE = 128_000
internal const val PCM_BYTES_PER_FRAME = 2 * CHANNELS
internal const val DRAIN_TIMEOUT_US = 10_000L

/** 微秒/秒，PTS 换算用 */
internal const val US_PER_SECOND = 1_000_000L

/** 每次从 AudioRecord 读多少 PCM 字节 */
internal const val PCM_BUFFER_SIZE = 8_192

/** 编码器输入缓冲上限（AAC 一帧远小于此） */
internal const val MAX_INPUT_SIZE = 16 * 1024

/** 停止收尾时的轮询间隔 */
internal const val STOP_POLL_MS = 5L

/**
 * 打开一对音频采集资源：AAC 编码器 + AudioRecord（MIC / 48k / 单声道 / PCM16）。
 *
 * 权限不足或初始化失败都会抛异常；失败时已创建的资源在这里释放干净，
 * 调用方无需关心清理。
 */
@SuppressLint("MissingPermission") // 权限由调用方在启动前校验，这里再兜一层
internal fun openAudioCapture(context: Context): Pair<MediaCodec, AudioRecord> {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        throw IllegalStateException("缺少录音权限")
    }
    val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
    }
    val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    } catch (e: Exception) {
        runCatching { codec.release() }
        throw e
    }

    val record = try {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, PCM_BUFFER_SIZE) * 2
        )
    } catch (e: Exception) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        throw e
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        runCatching { codec.stop() }
        runCatching { codec.release() }
        throw IllegalStateException("AudioRecord 初始化失败")
    }
    record.startRecording()
    return codec to record
}

/** 把一段 PCM 按编码器输入缓冲的空闲量分块送入；pts 随字节数递增 */
internal fun feedAudio(codec: MediaCodec, data: ByteArray, size: Int, ptsUs: Long) {
    var offset = 0
    var pts = ptsUs
    while (offset < size) {
        val index = try {
            codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
        } catch (e: Exception) {
            return
        }
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        val count = minOf(buffer.remaining(), size - offset)
        buffer.put(data, offset, count)
        codec.queueInputBuffer(index, 0, count, pts, 0)
        offset += count
        pts += count.toLong() * US_PER_SECOND / (SAMPLE_RATE * PCM_BYTES_PER_FRAME)
    }
}

/** 给编码器排一个 EOS（输入面已经无法再喂数据时用） */
internal fun queueEos(codec: MediaCodec, ptsUs: Long): Boolean = try {
    val index = codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
    if (index < 0) {
        false
    } else {
        codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        true
    }
} catch (e: Exception) {
    false
}

/** 把编码器输出缓冲复制成 ByteArray（缓冲要立刻归还编码器，不能长期持有） */
internal fun copyOut(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
    val data = ByteArray(info.size)
    buffer.position(info.offset)
    buffer.limit(info.offset + info.size)
    buffer.get(data)
    return data
}
