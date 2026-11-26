package com.dsh.bodycam.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.view.Surface
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
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
internal const val VIDEO_MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
internal const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * AAC-LC 的 RFC 6381 codec 字符串，与下面编码器用的
 * `AACObjectLC`（audio object type 2）对应。
 *
 * media3 的 muxer 靠这个字符串判断「AAC 是否每个样本都是同步样本」：
 * 不填 → 判定为非同步 → trun 里 190 个音频样本全被标 `sample_is_non_sync_sample`，
 * 播放器因此丢弃整条音轨（有声画、无声音）。
 */
internal const val AAC_CODECS_LC = "mp4a.40.2"

/**
 * 视频编码档位 → MediaCodec MIME。
 *
 * 只有 [AppConfig.VIDEO_CODEC_HEVC] 会走 HEVC，其余（含历史存下的非法值）
 * 一律回退 H.264——H.264 是所有 Android 设备保证有的编码器。
 */
internal fun videoMimeOf(codecType: Int): String =
    if (codecType == AppConfig.VIDEO_CODEC_HEVC) VIDEO_MIME_HEVC else VIDEO_MIME

/** 本机编码器支持的全部 MIME（小写）；只枚举一次，读设置时不再反复扫编码器 */
private val encoderMimes: Set<String> by lazy {
    try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }
}

/** 该视频编码格式在本机是否有编码器可用 */
internal fun isVideoCodecSupported(codecType: Int): Boolean {
    val mimes = encoderMimes
    // 枚举失败时保守放行 H.264：它是 Android 保证有的，漏判会连默认档都用不了
    if (mimes.isEmpty()) return codecType == AppConfig.VIDEO_CODEC_AVC
    return videoMimeOf(codecType).lowercase() in mimes
}

/** 本机可选的视频编码档位；H.264 永远在内（系统保证） */
internal fun supportedVideoCodecs(): List<Int> =
    AppConfig.VIDEO_CODEC_LABELS.indices.filter { isVideoCodecSupported(it) }
        .ifEmpty { listOf(AppConfig.VIDEO_CODEC_AVC) }

/** 每个编码格式的编码器能力上限只查一次 */
private val encoderMaxSizeCache = java.util.concurrent.ConcurrentHashMap<Int, android.util.Size?>()

/**
 * 查指定编码格式的编码器支持的最大宽×高；查不到返回 null（按不限制处理）。
 *
 * 取所有实现的能力的上限：设备上常有多个实现（硬编 + 软编），只要有一个
 * 编得动，`MediaCodec.createEncoderByType` 就有机会选中它。编码器能力跟
 * 摄像头无关，按编码格式缓存一次。
 *
 * 必须按**实际要用的编码格式**查：H.264 编码器能编 4K 不代表 H.265 也能，
 * 混用会挑到开录即失败的档位。
 */
internal fun videoEncoderMaxSize(codecType: Int): android.util.Size? =
    encoderMaxSizeCache.getOrPut(codecType) {
        try {
            val mime = videoMimeOf(codecType)
            var bestW = 0
            var bestH = 0
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val vc = runCatching { caps.videoCapabilities }.getOrNull() ?: continue
                bestW = maxOf(bestW, vc.supportedWidths.upper)
                bestH = maxOf(bestH, vc.supportedHeights.upper)
            }
            if (bestW > 0 && bestH > 0) android.util.Size(bestW, bestH) else null
        } catch (e: Exception) {
            null
        }
    }

internal const val DRAIN_TIMEOUT_US = 10_000L

/**
 * 一次录制用到的音频参数。
 *
 * 原本采样率与声道数是包内常量，现在可由设置页调整，所以随 [openAudioCapture]
 * 传入并在各录制器里保存下来——PTS 换算必须用**实际采用**的参数，否则音轨
 * 时长会与真实时间对不上。
 *
 * 预录与录像必须用同一份 [AudioConfig]：交接的 AAC 帧要写进同一条音轨，
 * 参数不同会直接串音或错位。
 */
/** 采集用的声道掩码；1 声道 → MONO，其余 → STEREO */
private fun channelInMaskOf(channels: Int): Int =
    if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

class AudioConfig(val sampleRate: Int, val channels: Int) {

    /** 每帧 PCM 字节数（16bit，故乘以 2） */
    val pcmBytesPerFrame: Int = 2 * channels

    /** 采集用的声道掩码 */
    val inChannelMask: Int = channelInMaskOf(channels)

    /** AAC 码率：双声道比单声道翻倍，否则每个声道分到的比特太少 */
    val bitrate: Int = MONO_BITRATE * channels

    /** 参数是否一致；预录交接、缓存复用都靠它判断能否续用 */
    fun matches(other: AudioConfig?): Boolean =
        other != null && other.sampleRate == sampleRate && other.channels == channels

    override fun toString(): String = "${sampleRate}Hz/${channels}ch"

    companion object {
        /** 单声道基准码率；双声道按倍数放大 */
        private const val MONO_BITRATE = 128_000

        /** 设置里的默认档；各录制器的参数默认值与未初始化状态都用它 */
        val DEFAULT: AudioConfig = AudioConfig(
            AppConfig.AUDIO_SAMPLE_RATE_DEFAULT, AppConfig.AUDIO_CHANNELS_DEFAULT
        )
    }
}

/** 从设置读取当前音频参数 */
internal fun audioConfigFromSettings(context: Context): AudioConfig = AudioConfig(
    sampleRate = SettingsStore.audioSampleRate(context),
    channels = SettingsStore.audioChannels(context)
)

/**
 * 该采样率/声道组合在本机能否采集。
 *
 * `AudioRecord.getMinBufferSize` 对不支持的组合返回负错误码，用它探测比重试
 * 更便宜，也不会真的占用麦克风。设备没有立体声麦克风时 `CHANNEL_IN_STEREO`
 * 会落在这里返回 false。
 */
internal fun isAudioConfigSupported(sampleRate: Int, channels: Int): Boolean = try {
    AudioRecord.getMinBufferSize(
        sampleRate, channelInMaskOf(channels), AudioFormat.ENCODING_PCM_16BIT
    ) > 0
} catch (e: Exception) {
    false
}

/** 当前设备可用的采样率档位（保底返回默认档，避免设置页一项都没有） */
internal fun supportedAudioSampleRates(): List<Int> {
    val ok = AppConfig.AUDIO_SAMPLE_RATE_OPTIONS.filter {
        isAudioConfigSupported(it, AppConfig.AUDIO_CHANNELS_MONO)
    }
    return ok.ifEmpty { listOf(AppConfig.AUDIO_SAMPLE_RATE_DEFAULT) }
}

/** 当前设备可用的声道档位（保底返回默认档） */
internal fun supportedAudioChannels(): List<Int> {
    val ok = AppConfig.AUDIO_CHANNELS_OPTIONS.filter {
        isAudioConfigSupported(AppConfig.AUDIO_SAMPLE_RATE_DEFAULT, it)
    }
    return ok.ifEmpty { listOf(AppConfig.AUDIO_CHANNELS_DEFAULT) }
}

/** 微秒/秒，PTS 换算用 */
internal const val US_PER_SECOND = 1_000_000L

/** 每次从 AudioRecord 读多少 PCM 字节 */
internal const val PCM_BUFFER_SIZE = 8_192

/**
 * 预录音频线程的读取块大小。
 *
 * `AudioRecord.read()` 会一直阻塞到凑满请求的字节数：8192 字节在 48kHz 单声道下
 * 是 85ms，而预录交接要 join 这个线程——它若正卡在读里，join 就得等最多 85ms
 * （实测 152ms）。这段时间视频编码器没人取输出，GL 被反压、相机丢帧，成片里
 * 就是交接处那段空洞。2048 字节 = 21ms，正好也够一个 AAC 帧，join 一下就回来。
 */
internal const val PCM_CHUNK_SIZE = 2_048

/** 编码器输入缓冲上限（AAC 一帧远小于此） */
internal const val MAX_INPUT_SIZE = 16 * 1024

/** 停止收尾时的轮询间隔 */
internal const val STOP_POLL_MS = 5L

/**
 * 建一个 Surface 输入的视频编码器并启动，返回 (编码器, 输入 Surface)。
 *
 * [SegmentRecorder] 与 [PreRecordBuffer] 用的是同一套配置，唯一差别是
 * 生命周期归属，所以只把「建 + 启」这一步集中。
 *
 * 调用方负责 release。中途抛异常时已建的编码器在这里释放干净。
 * I 帧间隔固定 1 秒：分段切换与预录交接都靠它保证能在 1 秒内拿到关键帧。
 */
internal fun openVideoEncoder(
    codecType: Int,
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int
): Pair<MediaCodec, Surface> {
    val mime = videoMimeOf(codecType)
    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val codec = MediaCodec.createEncoderByType(mime)
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        return codec to surface
    } catch (e: Exception) {
        runCatching { codec.release() }
        throw e
    }
}

/**
 * 打开一对音频采集资源：AAC 编码器 + AudioRecord。
 *
 * 采样率/声道由 [config] 决定，采集与编码必须用同一份参数——两者的
 * 采样率不一致会让 PTS 换算与实际时长不符。
 *
 * 权限不足或初始化失败都会抛异常；失败时已创建的资源在这里释放干净，
 * 调用方无需关心清理。
 */
@SuppressLint("MissingPermission") // 权限由调用方在启动前校验，这里再兜一层
internal fun openAudioCapture(
    context: Context,
    config: AudioConfig
): Pair<MediaCodec, AudioRecord> {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        throw IllegalStateException("缺少录音权限")
    }
    val format = MediaFormat.createAudioFormat(AUDIO_MIME, config.sampleRate, config.channels)
        .apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
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
            config.sampleRate, config.inChannelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            config.inChannelMask,
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
internal fun feedAudio(
    codec: MediaCodec,
    data: ByteArray,
    size: Int,
    ptsUs: Long,
    config: AudioConfig
) {
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
        // 每帧 PCM 时长 = 字节数 / (采样率 × 每帧字节数)
        pts += count.toLong() * US_PER_SECOND / (config.sampleRate * config.pcmBytesPerFrame)
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
