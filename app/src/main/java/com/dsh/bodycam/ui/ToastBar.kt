package com.dsh.bodycam.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow
import android.widget.TextView
import com.dsh.bodycam.R
import java.lang.ref.WeakReference

/**
 * 全局提示条：替代系统 Toast，位置固定在窗口中下部。
 *
 * 为什么不用 [android.widget.Toast]：Android 11（API 30）起系统会忽略文本 Toast 的
 * setGravity，一律钉在屏幕底部，拿不到「中下」这个位置。这里用 [PopupWindow]
 * 自绘一条，位置、样式、互斥关系全部可控。
 *
 * 三条行为约定：
 *  - **同一时刻只显示一条**：新提示到来时，如果上一条还挂着，就**复用同一个窗口**
 *    只换文字，不销毁重建。窗口重建会有一帧背景暴露，叠在高对比的取景画面上就是
 *    一下闪烁，所以复用是消除闪烁的关键，不只是加动画。
 *  - **进出有淡入淡出**：首条淡入；超时淡出后才真正关闭；淡出途中来了新提示，
 *    直接把窗口拉回不透明接着用，不会出现「旧的没了、新的还没出来」的空档。
 *  - **跟随 Activity 生命周期**：PopupWindow 强引用 Activity，Activity 走完
 *    onDestroy 必须 [release]，否则会把它连同窗口一起泄漏。
 *
 * 提示条本身不接收触摸（不可触摸且不抢焦点），出现期间用户照常可以点页面上的控件。
 */
object ToastBar {

    /** 提示停留时长；比系统 Toast LENGTH_SHORT（2s）略短，减少对操作的干扰 */
    private const val DURATION_MS = 1_800L

    /** 淡入 / 淡出时长；够短到不拖沓，又足以遮住窗口上屏的一次跳变 */
    private const val ENTER_MS = 140L
    private const val EXIT_MS = 120L

    /**
     * 距窗口底部的偏移占窗口高度的比例。
     *
     * 0.28 让提示条落在中下位置：避开底部操作坞/录音按钮，
     * 又不至于顶到画面中央干扰取景。
     */
    private const val BOTTOM_FRACTION = 0.28f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val dismissRunnable = Runnable { dismissCurrent(animate = true) }

    /**
     * 当前这条提示的窗口；null 表示没有提示在显示。
     *
     * 这里保留强引用是有意为之：窗口只在「显示中」这段时间存在，超时或释放后
     * 立刻置 null（见 [dismissNow]），生命周期比 Activity 短得多。
     */
    private var popup: PopupWindow? = null

    /**
     * 内容视图用弱引用。
     *
     * [ToastBar] 是静态单例，若强引用 TextView，就从静态字段一路指回 Activity
     * ——这正是 `StaticFieldLeak` 报的问题。窗口本身（[popup]）虽然也持有视图，
     * 但它随 dismiss 立即释放，这里再持一份强引用只会延长寿命而没有任何好处。
     */
    private var label: WeakReference<TextView>? = null

    /**
     * 当前提示所属的 Activity，用弱引用。
     *
     * 同样的静态单例问题：强引用会让 Activity 在销毁后被单例拖住。
     * 弱引用下即使某处漏调 [release]，Activity 仍能被正常回收，
     * 只是这条提示不会被主动撤掉——可接受的降级，总比泄漏好。
     */
    private var owner: WeakReference<Activity>? = null

    /** 正在跑的淡入或淡出；同一时刻只有一个 */
    private var fadeAnim: ObjectAnimator? = null

    /** 当前提示的宿主 Activity；已被回收时返回 null */
    private fun ownerActivity(): Activity? = owner?.get()

    /** 当前提示的内容视图；已被回收时返回 null */
    private fun labelView(): TextView? = label?.get()

    /**
     * 显示一条提示。调用方必须在主线程。
     *
     * 上一条还在显示时**不会重建窗口**，只替换文字并重置停留计时——这样连续的
     * 提示之间没有窗口销毁/创建的空档，取景画面不会透出来闪一下。
     */
    fun show(activity: Activity, message: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (message.isBlank()) return

        val existing = popup
        val view = labelView()
        if (existing != null && view != null && ownerActivity() === activity && existing.isShowing) {
            // 复用：换文字，窗口保持上屏。文案长度会变，必须重测一次尺寸，
            // 否则窗口留着上一次的宽高，变长的那条会被裁掉。
            view.text = message
            resizeToContent(activity, existing, view)
            // 上一条如果在淡出途中，把它拉回不透明；正常显示时这里等于空操作
            fadeToOpaque(view)
            restartTimer()
            return
        }

        dismissNow()

        val content = buildContent(activity, message)
        val created = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // 不抢焦点：弹着也能继续点页面
        ).apply {
            isOutsideTouchable = false
            isTouchable = false
            setBackgroundDrawable(null)
        }

        val anchor = activity.window?.decorView ?: return
        if (!anchor.isAttachedToWindow) {
            // 窗口还没挂上（例如在 onCreate 里就提示）：等一帧再来
            anchor.post { show(activity, message) }
            return
        }

        // 必须先置透明再上屏：如果等 showAtLocation 之后再归零，
        // 窗口的第一帧会以不透明状态画出来，接着才跳成透明开始淡入，
        // 那一帧就是用户看到的「闪一下」。
        content.alpha = 0f

        try {
            created.showAtLocation(
                anchor,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                (anchor.height * BOTTOM_FRACTION).toInt()
            )
        } catch (e: Exception) {
            // 窗口正在销毁等场景：静默放弃这条提示，不影响业务
            return
        }

        popup = created
        label = WeakReference(content)
        owner = WeakReference(activity)
        startFade(content, 0f, 1f, ENTER_MS)
        restartTimer()
    }

    /**
     * 释放与指定 Activity 相关的提示。
     *
     * 宿主 Activity 在 onDestroy 里调用。只有当前提示确实属于该 Activity 时才撤，
     * 避免把别处刚显示的新提示误撤掉。这里不做淡出：Activity 正在销毁，
     * 延迟关闭反而会撞上窗口已失效。
     */
    fun release(activity: Activity) {
        if (ownerActivity() === activity) dismissNow()
    }

    // ------------------------------------------------------------ 计时

    private fun restartTimer() {
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, DURATION_MS)
    }

    /**
     * 换了新文案后按内容重新量一次尺寸并更新窗口。
     *
     * TextView 是 WRAP_CONTENT 的，但 PopupWindow 上屏后不会跟着内容自动改大小，
     * 必须显式 measure + update。量不出来（窗口已失效）就跳过，
     * 这条提示沿用旧尺寸显示，不影响文字本身——下一条会重建窗口。
     */
    private fun resizeToContent(activity: Activity, win: PopupWindow, view: View) {
        try {
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(widthSpec, heightSpec)
            win.update(view.measuredWidth, view.measuredHeight)
        } catch (e: Exception) {
            // 窗口已失效：保持原尺寸，下一条提示会重建
        }
    }

    // ------------------------------------------------------------ 动画

    /**
     * 复用已有窗口时把不透明度拉满。
     *
     * 正常显示中 alpha 已经是 1，直接返回；只有上一条正在淡出时才需要补一段
     * 短动画升回来——从当前值接着走，不归零重来，视觉上不会「呼吸」一下。
     */
    private fun fadeToOpaque(view: View) {
        if (view.alpha >= 0.99f) return
        startFade(view, view.alpha, 1f, ENTER_MS)
    }

    /** 跑一段透明度动画；同一时刻只允许一个，新的会取消旧的 */
    private fun startFade(view: View, from: Float, to: Float, durationMs: Long) {
        cancelFade()
        fadeAnim = ObjectAnimator.ofFloat(view, View.ALPHA, from, to).apply {
            duration = durationMs
            interpolator =
                if (to >= from) DecelerateInterpolator() else AccelerateInterpolator()
            start()
        }
    }

    // ------------------------------------------------------------ 关闭

    /** 超时收尾：淡出后再真正关闭；[animate] = false 时立即关闭（释放路径用） */
    private fun dismissCurrent(animate: Boolean) {
        mainHandler.removeCallbacks(dismissRunnable)

        val win = popup
        val view = labelView()
        if (win == null || view == null || !win.isShowing) {
            dismissNow()
            return
        }
        if (!animate) {
            dismissNow()
            return
        }

        cancelFade()
        // 淡出期间若被新提示复用，fadeAnim 会被换成新的、popup 也可能不是这条了，
        // 两个条件任一不成立都说明这条已被接管，不能再关窗口
        val fading = ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f).apply {
            duration = EXIT_MS
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fadeAnim !== animation) return
                    fadeAnim = null
                    if (popup === win) dismissNow()
                }
            })
            start()
        }
        fadeAnim = fading
    }

    /** 立即关闭并清空所有状态 */
    private fun dismissNow() {
        cancelFade()
        val win = popup
        popup = null
        label = null
        owner = null
        if (win != null) {
            try {
                win.dismiss()
            } catch (e: Exception) {
                // 窗口已随 Activity 一起销毁，忽略
            }
        }
    }

    /** 取消当前动画。先置空引用再 cancel，cancel 触发的回调会因此直接返回 */
    private fun cancelFade() {
        val running = fadeAnim
        fadeAnim = null
        running?.cancel()
    }

    // ------------------------------------------------------------ 内容

    /** 提示条内容：复用设计令牌，与其他浮层保持同一套视觉 */
    private fun buildContent(activity: Activity, message: CharSequence): TextView {
        val density = activity.resources.displayMetrics.density
        val padH = activity.resources.getDimension(R.dimen.space_md).toInt()
        val padV = activity.resources.getDimension(R.dimen.space_sm).toInt()
        val maxTextWidth =
            (activity.resources.displayMetrics.widthPixels * 0.82f).toInt() - padH * 2

        return TextView(activity).apply {
            text = message
            setTextColor(activity.getColor(R.color.ink))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.text_md))
            gravity = Gravity.CENTER
            setMaxWidth(maxTextWidth.coerceAtLeast(1))
            setPadding(padH, padV, padH, padV)
            setBackgroundResource(R.drawable.bg_toast)
            // 多行时行距太挤会读不清，给一点呼吸
            setLineSpacing(2f * density, 1f)
            // 提示条纯展示，不消费任何触摸
            isClickable = false
            isFocusable = false
        }
    }
}