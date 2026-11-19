package com.dsh.bodycam.key

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsh.bodycam.core.TriggerRouter

/**
 * 外接按键广播的入口：收到广播后转给 [TriggerRouter]。
 *
 * 这里只做转发，action 是否有效、当前该不该拦截都由 [TriggerRouter] 判断。
 * 因为 action 是运行期配置，无法写死在清单里，所以由 MainActivity 动态注册
 * （registerReceiver + IntentFilter，未配置任何 action 时不注册）。
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        TriggerRouter.handleBroadcast(context, intent?.action)
    }
}