package com.carhud.speedtrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 車機開機與喚醒廣播接收器
 * 攔截系統開機完成、快速開機、解鎖及更新完成事件，全自動啟動神盾測速與 HUD 懸浮球常駐
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBootReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_START_HUD = "auto_start_hud"
        const val KEY_AUTO_START_SPEEDTRAP = "auto_start_speedtrap"

        private var lastBootProcessedElapsed: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "⚡ 收到開機/喚醒事件廣播: $action")

        val nowElapsed = SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val autoStartSpeedTrap = prefs.getBoolean(KEY_AUTO_START_SPEEDTRAP, true)

        // 1. 自動啟動 GPS 車速引擎
        try {
            GpsSpeedManager.startListening(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 GPS 引擎失敗: ${e.message}")
        }

        // 2. 自動在背景喚醒神盾測速 (內建 120 秒防重複與冷啟動保護)
        if (autoStartSpeedTrap) {
            if (lastBootProcessedElapsed == 0L || (nowElapsed - lastBootProcessedElapsed) >= 30_000L) {
                Log.i(TAG, "🚀 開機自啟：正在自動喚醒神盾測速 App...")
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)
                lastBootProcessedElapsed = nowElapsed
            }
        }

        // 3. 自動啟動全域置頂 HUD 懸浮球常駐 (多廣播覆蓋，保證上電立即可見)
        TopHudOverlayService.startIfEnabled(context)
    }
}
