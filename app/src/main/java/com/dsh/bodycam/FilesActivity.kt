package com.dsh.bodycam

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.dsh.bodycam.util.Storage
import com.dsh.bodycam.util.StoragePermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 文件管理页：三个 tab（图片 / 视频 / 音频），分别列出
 * `/sdcard/BodyCam/{Image,Video,Audio}/yyyyMMdd/` 下的文件，最新的在最上面。
 *
 * 点文件进入 [ViewerActivity]：图片放大查看，视频与音频播放。
 * 不引入 TabLayout / RecyclerView，用系统控件保持依赖最小。
 */
class FilesActivity : Activity() {

    companion object {
        private const val TAB_IMAGES = 0
        private const val TAB_VIDEOS = 1
        private const val TAB_AUDIOS = 2

        fun launch(context: Context) {
            context.startActivity(Intent(context, FilesActivity::class.java))
        }
    }

    private lateinit var tabs: List<TextView>
    private lateinit var fileList: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: FileAdapter

    private var currentTab = TAB_IMAGES

    /** 目录扫描线程；页面销毁时关掉 */
    private val scanExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        tabs = listOf(
            findViewById<TextView>(R.id.tabImages).also { it.setOnClickListener { select(TAB_IMAGES) } },
            findViewById<TextView>(R.id.tabVideos).also { it.setOnClickListener { select(TAB_VIDEOS) } },
            findViewById<TextView>(R.id.tabAudios).also { it.setOnClickListener { select(TAB_AUDIOS) } }
        )
        fileList = findViewById(R.id.fileList)
        emptyView = findViewById(R.id.emptyView)

        adapter = FileAdapter()
        fileList.adapter = adapter
        fileList.setOnItemClickListener { _, _, position, _ ->
            adapter.items.getOrNull(position)?.let { open(it) }
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        select(TAB_IMAGES)
    }

    override fun onResume() {
        super.onResume()
        // 从查看页回来、或刚授完权限回来，都重新扫一遍目录
        reload()
    }

    override fun onDestroy() {
        scanExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ tab

    private fun select(tab: Int) {
        currentTab = tab
        tabs.forEachIndexed { index, view ->
            val active = index == tab
            view.setTextColor(getColor(if (active) R.color.accent else R.color.ink_2))
        }
        reload()
    }

    // ------------------------------------------------------------ 列表

    private fun kindOf(tab: Int): Storage.Kind = when (tab) {
        TAB_VIDEOS -> Storage.Kind.VIDEO
        TAB_AUDIOS -> Storage.Kind.AUDIO
        else -> Storage.Kind.IMAGE
    }

    private fun reload() {
        val kind = kindOf(currentTab)
        // 目录扫描放到后台：文件可能有上千个，主线程上一帧都别赌
        scanExecutor.execute {
            val items = Storage.list(this, kind)
            val denied = !StoragePermission.isGranted(this)
            runOnUiThread {
                // 扫描期间用户可能已经切了 tab，结果过期就丢掉
                if (currentTab != tabOf(kind) || isFinishing) return@runOnUiThread
                adapter.items = items
                adapter.notifyDataSetChanged()
                when {
                    denied -> {
                        emptyView.text = getString(R.string.files_need_permission)
                        emptyView.visibility =
                            if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                    items.isEmpty() -> {
                        emptyView.text = getString(R.string.files_empty)
                        emptyView.visibility = View.VISIBLE
                    }
                    else -> emptyView.visibility = View.GONE
                }
            }
        }
    }

    private fun tabOf(kind: Storage.Kind): Int = when (kind) {
        Storage.Kind.VIDEO -> TAB_VIDEOS
        Storage.Kind.AUDIO -> TAB_AUDIOS
        Storage.Kind.IMAGE -> TAB_IMAGES
    }

    private fun open(item: Storage.MediaFile) {
        ViewerActivity.launch(this, item.file.absolutePath, kindOf(currentTab))
    }

    // ------------------------------------------------------------ 适配器

    private inner class FileAdapter : BaseAdapter() {

        var items: List<Storage.MediaFile> = emptyList()

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Storage.MediaFile = items[position]

        override fun getItemId(position: Int): Long = items[position].name.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@FilesActivity)
                    .inflate(R.layout.item_file, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.nameView).text = item.name
            view.findViewById<TextView>(R.id.metaView).text = getString(
                R.string.file_meta_fmt,
                formatSize(item.length),
                timeFormat.format(Date(item.modified))
            )
            return view
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(
                Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)
            )
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}