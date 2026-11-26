package com.dsh.bodycam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.ui.PlayerView
import com.dsh.bodycam.ui.ToastBar
import com.dsh.bodycam.ui.ZoomableImageView
import com.dsh.bodycam.util.MuxerFix
import com.dsh.bodycam.util.Storage
import com.dsh.bodycam.util.VideoCrypto
import java.io.File

/**
 * 媒体查看页：图片放大查看 / 视频播放 / 音频播放，三种媒体共用一个壳。
 *
 * 图片：用 [ZoomableImageView]，支持双指缩放、拖动、双击放大。
 * 视频与音频：都走 media3 [ExoPlayer] + [PlayerView] 自带控制条
 * （播放/暂停键、进度条、时间显示全部是 ExoPlayer 官方样式，页面不另画控件）。
 *
 * 为什么不用系统 [android.widget.VideoView] / [android.media.MediaPlayer]：
 * 本工程的成片是 **fMP4**（moof+mdat 分片、moov 无样本索引），系统 MediaPlayer
 * 拖动进度条做 seek 时报 `MEDIA_ERROR_UNKNOWN`（error -2147483648）。media3 的
 * `FragmentedMp4Extractor` 会为分片建立样本索引，seek 正常。
 */
class ViewerActivity : Activity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_KIND = "kind"

        /** 控制条显示后多久自动收起 */
        private const val CONTROLS_TIMEOUT_MS = 3000L

        fun launch(context: Context, path: String, kind: Storage.Kind) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra(EXTRA_PATH, path)
            intent.putExtra(EXTRA_KIND, kind.name)
            context.startActivity(intent)
        }
    }

    private lateinit var titleView: TextView
    private lateinit var imageView: ZoomableImageView
    private lateinit var playerView: PlayerView
    private lateinit var audioIcon: ImageView
    private lateinit var loadingOverlay: android.view.View
    private lateinit var passphraseOverlay: android.view.View
    private lateinit var passphraseInput: android.widget.EditText
    private lateinit var passphraseError: TextView

    private var player: ExoPlayer? = null

    /** 解密出的临时明文文件，退出播放页时删掉 */
    private var decryptedTemp: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        titleView = findViewById(R.id.titleView)
        imageView = findViewById(R.id.imageView)
        playerView = findViewById(R.id.playerView)
        audioIcon = findViewById(R.id.audioIcon)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        passphraseOverlay = findViewById(R.id.passphraseOverlay)
        passphraseInput = findViewById(R.id.passphraseInput)
        passphraseError = findViewById(R.id.passphraseError)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        padControlRows()

        val path = intent.getStringExtra(EXTRA_PATH)
        val kind = runCatching {
            Storage.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "")
        }.getOrDefault(Storage.Kind.IMAGE)

        if (path == null) {
            toast(getString(R.string.viewer_play_failed))
            finish()
            return
        }

        val file = File(path)
        titleView.text = file.name
        when (kind) {
            Storage.Kind.IMAGE -> showImage(file)
            // 视频可能是加密的，音频不会（录音不加密）
            Storage.Kind.VIDEO -> {
                if (VideoCrypto.isEncrypted(file)) askPassphrase(file, audio = false)
                else showMedia(file, audio = false)
            }
            Storage.Kind.AUDIO -> showMedia(file, audio = true)
        }
    }

    // ------------------------------------------------------------ 解密

    /**
     * 加密录像：先让用户输口令，再解密成临时明文文件交给播放链路。
     *
     * 为什么落临时文件而不是自定义 DataSource 边读边解：本工程的加密是整文件
     * 分块（不是帧级），`ExoPlayer` 的 extractor 需要随机访问容器结构，边读边解
     * 得自己实现 `DataSource` 并重做 seek 语义，改动大且容易在拖动进度条时出错。
     * 落地明文再标准播放最稳；临时文件在退出播放页时立即删除。
     */
    private fun askPassphrase(file: File, audio: Boolean) {
        passphraseOverlay.visibility = View.VISIBLE
        passphraseError.visibility = View.GONE
        passphraseInput.setText("")
        passphraseInput.requestFocus()

        findViewById<View>(R.id.btnPassphraseConfirm).setOnClickListener {
            val input = passphraseInput.text.toString()
            val err = when {
                input.isEmpty() -> getString(R.string.viewer_passphrase_empty)
                !VideoCrypto.isValidPassphrase(input) ->
                    getString(R.string.viewer_passphrase_wrong_len, input.length)
                else -> null
            }
            if (err != null) {
                passphraseError.text = err
                passphraseError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            decryptThenPlay(file, input, audio)
        }
    }

    /** 后台解密 → 成功后走正常播放；失败提示口令错或文件被篡改 */
    private fun decryptThenPlay(file: File, passphrase: String, audio: Boolean) {
        passphraseError.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        val temp = File(cacheDir, "dec_${System.currentTimeMillis()}.mp4")

        Thread {
            val ok = runCatching {
                VideoCrypto.decryptTo(file, passphrase, temp)
            }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (!ok) {
                    loadingOverlay.visibility = View.GONE
                    passphraseError.text = getString(R.string.viewer_passphrase_wrong)
                    passphraseError.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                // 解密成功：上一份临时明文（若有）立刻删，避免反复解密堆积
                decryptedTemp?.delete()
                decryptedTemp = temp
                passphraseOverlay.visibility = View.GONE
                showMedia(temp, audio)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }


    override fun onDestroy() {
        ToastBar.release(this)
        playerView.player = null
        player?.release()
        player = null
        // 明文临时文件必须删干净：留在缓存目录里等于加密白做了
        decryptedTemp?.delete()
        decryptedTemp = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 图片

    private fun showImage(file: File) {
        imageView.visibility = View.VISIBLE
        val decoded = runCatching { decodeScaled(file) }.getOrNull()
        if (decoded == null) {
            toast(getString(R.string.viewer_image_failed))
            return
        }
        imageView.setImageBitmap(decoded)
        // 布局还没量过时 reset 拿不到宽高，等一次 layout
        imageView.post { imageView.reset() }
    }

    /**
     * 按屏幕尺寸降采样解码：相机原图 1080×1920 尚可，但放大到 4 倍时
     * 再叠一份全尺寸位图容易触发 OOM，先读边界再按 inSampleSize 缩一次。
     */
    private fun decodeScaled(file: File): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val metrics = resources.displayMetrics
        val targetW = maxOf(metrics.widthPixels, 1)
        val targetH = maxOf(metrics.heightPixels, 1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    }

    // ------------------------------------------------------------ 视频 / 音频

    /**
     * 视频与音频共用同一个播放链路，唯一差别是音频没有画面：
     * PlayerView 照常存在（控制条要它承载），只是背后垫一个大话筒图标。
     *
     * `DefaultExtractorsFactory` 与 `setFragmentedMp4ExtractorFlags` 都是 media3 的
     * `@UnstableApi`：官方只对打了这个标记的接口保留「不保证跨版本二进制兼容」。
     * 本工程锁定 media3 1.11.1，升级版本时回归一次播放/拖动即可，故直接 opt-in。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showMedia(file: File, audio: Boolean) {
        playerView.visibility = View.VISIBLE
        if (audio) {
            audioIcon.visibility = View.VISIBLE
            // PlayerView 盖在图标上面：把它的遮罩底设成透明，才能看到背后的大话筒；
            // 控制条本身不受影响，播放/暂停、进度、时间照常可用
            playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        loadingOverlay.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    this,
                    DefaultExtractorsFactory().setFragmentedMp4ExtractorFlags(
                        FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP
                    )
                )
            )
            .build()
        player = exo
        playerView.player = exo

        // 控制条显隐交给 media3 自带机制：
        // - hide_on_touch=true：点画面就是切换——隐藏时点出来、显示时再点立刻收起
        //   （正是「方便全屏看」要的行为，也是官方标准做法）；
        // - show_timeout=3s：显示后 3 秒无操作自动收起；
        // - auto_show=false：否则暂停/缓冲时会自己弹出来且不计时。
        // 点按钮、拖进度条这些触摸会被控制条内部子 View 消费，
        // 不会冒泡到画面的 click，所以操作时不会被收起。
        playerView.setControllerAutoShow(false)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerShowTimeoutMs(CONTROLS_TIMEOUT_MS.toInt())

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // STATE_READY 才代表 moov/mfra 读完、SeekMap 建好、控制条可以放开拖；
                // BUFFERING 期间（含首帧未解码）继续盖着，避免用户拖到未就绪的条上
                if (playbackState == Player.STATE_READY) {
                    hideLoading()
                    // 等遮罩淡出（150ms）后再显示控制条，否则会在遮罩底下闪一下
                    playerView.postDelayed({ playerView.showController() }, 250L)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hideLoading()
                toast(getString(R.string.viewer_play_failed) + " (" + error.errorCodeName + ")")
            }
        })

        // 播放前先过一遍 MuxerFix：历史录制文件由旧版 muxer 产出，音频样本被标成
        // 非同步样本，media3 会整条丢弃音轨（有声画无声音）；同时补齐 moov 里为 0
        // 的 duration，否则单轨文件会被当成空流直接 ENDED。修复幂等，已正常的文件
        // 不会被改动，扫描只读 box 头与 trun 样本 flags，不碰 mdat。
        Thread {
            runCatching { MuxerFix.patchDurations(file) }
                .onFailure { Log.w("ViewerActivity", "patch failed: ${file.name}", it) }
            runOnUiThread {
                val p = player ?: return@runOnUiThread
                p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                p.prepare()
                p.playWhenReady = true
            }
        }.start()
    }

    // ------------------------------------------------------------ 工具

    /**
     * 给播放控制条底部那一排的进度条与时长文本留出水平间隙。
     *
     * 两个节点都住在 `PlayerControlView` 内部的 `exo_player_control_view.xml`：
     *
     * - `exo_progress`（`DefaultTimeBar`）：**绘制范围与触摸热区都取自 `seekBounds`**，
     *   而 `seekBounds` 的左右边界正是 `getPaddingLeft/Right()`（`DefaultTimeBar.onLayout`），
     *   所以设水平 padding 能同时把「进度条两端」和「可拖拽热区」一并向内收。
     * - `exo_time`（左侧「当前 / 总时长」）：XML 里自带 10dp 侧边距，这里统一成同档。
     *
     * 不加 `exo_basic_controls`（右下按钮组，含齿轮）：按钮自身带内边距，
     * 再套一层会显得离屏幕沿太远，右侧按钮组保持原样。
     *
     * 不这么做的话，进度条会从屏幕最左画到最右，拖到片头/片尾时手指要压到
     * 屏幕边缘，容易触发系统返回手势或被导航条吃掉。
     *
     * 取 `space_sm`（12dp）而不是更宽的档位：进度条本身还要再让出半个拖拽圆点
     * （`scrubberPadding` 7.5dp），实际视觉端点约为 19dp，再宽就明显浪费横向长度了。
     */
    private fun padControlRows() {
        val gap = resources.getDimensionPixelSize(R.dimen.space_sm)
        val ids = intArrayOf(
            androidx.media3.ui.R.id.exo_progress,
            androidx.media3.ui.R.id.exo_time
        )
        for (id in ids) {
            val v = playerView.findViewById<View?>(id) ?: continue
            v.setPadding(gap, v.paddingTop, gap, v.paddingBottom)
        }
    }

    /**
     * 撤掉加载遮罩。幂等：重复调用只是多设一次 visibility。
     *
     * 不直接 `setVisibility(GONE)` 而是带一层淡出，因为大文件在 `STATE_READY`
     * 边缘上常常还差一帧——硬切会让黑底和首帧闪一下。
     */
    private fun hideLoading() {
        if (loadingOverlay.visibility != View.VISIBLE) return
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                loadingOverlay.alpha = 1f
            }
            .start()
    }

    private fun toast(message: String) {
        ToastBar.show(this, message)
    }
}