package com.carhud.speedtrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 車機開機與喚醒廣播接收器
 * 攔截系統開機完成與快速開機事件，安全平穩地啟動 GPS 與 HUD 懸浮球常駐
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBootReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_START_HUD = "auto_start_hud"
        const val KEY_AUTO_START_SPEEDTRAP = "auto_start_speedtrap"

        // 開機冷卻鎖：避免系統在短時間內發送多個開機事件導致重複執行與畫面閃退
        private var lastBootProcessedElapsed: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "⚡ 收到開機/喚醒事件廣播: $action")

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastBootProcessedElapsed < 60000L) {
            Log.d(TAG, "60 秒內已執行過開機自啟，略過重複廣播: $action")
            return
        }
        lastBootProcessedElapsed = nowElapsed

        val prefs = try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "讀取設定失敗: ${e.message}")
            null
        }
        val autoStartSpeedTrap = prefs?.getBoolean(KEY_AUTO_START_SPEEDTRAP, true) ?: true

        // 1. 自動啟動 GPS 車速引擎
        try {
            GpsSpeedManager.startListening(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 GPS 引擎失敗: ${e.message}")
        }

        // 2. 開機時安全檢查神盾測速 (絕不頻繁開啟又關閉畫面，bringHomeToFront = false, isBoot = true)
        if (autoStartSpeedTrap) {
            Log.i(TAG, "🚀 開機自啟：靜態確認神盾測速狀態...")
            try {
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = false, isBoot = true)
            } catch (e: Exception) {
                Log.e(TAG, "開機喚醒神盾失敗: ${e.message}")
            }
        }

        // 3. 自動啟動全域置頂 HUD 懸浮球常駐 (保證上電立即可見)
        try {
            TopHudOverlayService.startIfEnabled(context)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 HUD 懸浮球失敗: ${e.message}")
        }
    }
}
