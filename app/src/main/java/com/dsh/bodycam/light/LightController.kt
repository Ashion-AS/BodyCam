package com.dsh.bodycam.light

import android.hardware.camera2.CameraManager
import android.util.Log
import com.dsh.bodycam.core.AppConfig
import java.io.File

/**
 * 补光 / 红外灯的统一入口。
 *
 * 灯有三条通路，按硬件接法选一条即可，调用方不必关心具体是哪条：
 *
 *  1. **相机闪光灯（torch）**——最通用的补光灯。当前摄像头自带 LED 时走预览
 *     重复请求的 `FLASH_MODE_TORCH`，录像中切换不打断编码；当前摄像头没有 LED
 *     （前摄）时，退到 [CameraManager.setTorchMode] 点亮另一颗带闪光灯的摄像头。
 *  2. **固定 sysfs 节点**——整机把灯接在 GPIO/LED 驱动上时，节点如
 *     `/sys/class/leds/ir/brightness`，写 `1` 开、`0` 关。节点路径由
 *     [AppConfig.LIGHT_NODE_TORCH] / [AppConfig.LIGHT_NODE_IR] 配置。
 *  3. **未接入**——节点为空且没有可用闪光灯时，[available] 返回 false，
 *     上层据此隐藏入口，不会出现「点了没反应」。
 *
 * 灯的语义分两类，各自独立开关、互不影响：
 *  - [Kind.TORCH] 白光补光
 *  - [Kind.IR]    红外灯（夜间补光，通常配合红外镜头；UI 入口待硬件确认）
 */
object LightController {

    private const val TAG = "BodyCamLight"

    /** 灯的种类。每条通路独立记录开关状态。 */
    enum class Kind { TORCH, IR }

    /** 各通路当前状态；键是通路标识，见 [CameraTorch.key] / [SysfsLight.key] */
    private val states = mutableMapOf<String, Boolean>()

    /** 由 MainActivity 注入的相机闪光灯通路；null 表示当前没有可用相机 */
    private var cameraTorch: CameraTorch? = null
    private var sysfsTorch: SysfsLight? = null
    private var sysfsIr: SysfsLight? = null

    fun init() {
        sysfsTorch = SysfsLight(Kind.TORCH, AppConfig.LIGHT_NODE_TORCH)
        sysfsIr = SysfsLight(Kind.IR, AppConfig.LIGHT_NODE_IR)
    }

    /** 注入相机闪光灯通路，由 MainActivity 实现（它才知道当前摄像头与后备摄像头） */
    fun attachCameraTorch(torch: CameraTorch?) {
        cameraTorch = torch
    }

    fun detachCameraTorch() {
        cameraTorch = null
    }

    fun isOn(kind: Kind): Boolean = when (kind) {
        Kind.TORCH -> cameraTorch?.isOn == true || sysfsTorch?.let { states[it.key] } == true
        Kind.IR -> sysfsIr?.let { states[it.key] } == true
    }

    /** 该种灯是否有可用通路；没有就隐藏入口 */
    fun available(kind: Kind): Boolean = when (kind) {
        Kind.TORCH -> cameraTorch?.available == true || sysfsTorch?.available == true
        Kind.IR -> sysfsIr?.available == true
    }

    /**
     * 切换开关。
     * @return true 表示状态已改变；false 表示没有可用通路或硬件拒绝
     */
    fun toggle(kind: Kind): Boolean = set(kind, !isOn(kind))

    fun set(kind: Kind, on: Boolean): Boolean {
        return when (kind) {
            Kind.TORCH -> setTorch(on)
            Kind.IR -> setIr(on)
        }
    }

    private fun setTorch(on: Boolean): Boolean {
        // 优先相机闪光灯：录像中也能切，且不需要任何权限外的节点
        val cam = cameraTorch
        if (cam != null && cam.available) {
            if (cam.set(on)) {
                states[cam.key] = on
                return true
            }
        }
        // 退到固定节点
        val node = sysfsTorch
        if (node != null && node.available) {
            val ok = node.set(on)
            if (ok) states[node.key] = on
            return ok
        }
        return false
    }

    private fun setIr(on: Boolean): Boolean {
        val node = sysfsIr
        if (node == null || !node.available) return false
        val ok = node.set(on)
        if (ok) states[node.key] = on
        return ok
    }

    /** 关掉所有灯并复位状态（切换摄像头 / 退出页面时调用） */
    fun releaseAll() {
        cameraTorch?.let { if (it.isOn) it.set(false) }
        sysfsTorch?.let { if (states[it.key] == true) it.set(false) }
        sysfsIr?.let { if (states[it.key] == true) it.set(false) }
        states.clear()
    }

    // ------------------------------------------------------------ 通路实现

    /**
     * 相机闪光灯通路。由 MainActivity 实现并注入，因为它要同时知道
     * 「当前 CameraController」和「带闪光灯的后备摄像头 id」。
     */
    interface CameraTorch {
        val key: String
        val available: Boolean
        val isOn: Boolean

        /** @return false 表示硬件拒绝（例如该 ROM 不允许点亮别的摄像头） */
        fun set(on: Boolean): Boolean
    }

    /** 固定 sysfs 节点的灯：写 "1" 开、"0" 关。节点为空 = 该灯未接入 */
    private class SysfsLight(val kind: Kind, private val path: String) {
        val key: String get() = "sysfs:${kind.name}:$path"
        val available: Boolean get() = path.isNotEmpty()

        fun set(on: Boolean): Boolean {
            if (!available) return false
            return try {
                val node = File(path)
                if (!node.exists()) {
                    Log.w(TAG, "节点不存在：$path")
                    return false
                }
                node.writeText(if (on) "1" else "0")
                Log.i(TAG, "${kind.name} -> $on ($path)")
                true
            } catch (e: Exception) {
                // 未 root / SELinux 限制 / 节点只读都会落到这里，不打扰用户
                Log.w(TAG, "${kind.name} 写节点失败：$path", e)
                false
            }
        }
    }
}