package com.dsh.bodycam.key

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.dsh.bodycam.core.KeyRouter

/**
 * 可选组件：让音量键在息屏 / 后台也能生效。
 *
 * Android 在屏幕关闭时会把音量键交给系统（调音量），普通 Activity 收不到。
 * 无障碍服务 + FLAG_REQUEST_FILTER_KEY_EVENTS 可以在系统分发前截获按键，
 * 这是唯一不需要 root 的可靠方案。
 *
 * 需要用户在「设置 → 无障碍」里手动开启；未开启时只有前台能用音量键。
 * 当 KeyRouter.armed 为 false（本应用既不在前台也不在录像）时会放行，不干扰正常调音量。
 */
class GlobalKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!KeyRouter.armed) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> KeyRouter.handleVolumeUp()
            KeyEvent.KEYCODE_VOLUME_DOWN -> KeyRouter.handleVolumeDown()
            else -> false
        }
    }
}
