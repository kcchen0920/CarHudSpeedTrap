package com.carhud.speedtrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 車機開機與喚醒廣播接收器
 * 攔截系統開機完成與快速開機事件，並具備 3 階段遞進自癒機制 (+0s, +4s, +10s, +20s)
 * 解決車機剛開機時硬體 GPS 晶片尚未通電與 NotificationListener 服務延遲綁定之問題。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBootReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_START_HUD = "auto_start_hud"
        const val KEY_AUTO_START_SPEEDTRAP = "auto_start_speedtrap"

        // 開機冷卻鎖：30 秒內不重複處理開機廣播
        private var lastBootProcessedElapsed: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "⚡ 收到開機/喚醒事件廣播: $action")

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastBootProcessedElapsed < 30000L) {
            Log.d(TAG, "30 秒內已執行過開機自啟，略過重複廣播: $action")
            return
        }
        lastBootProcessedElapsed = nowElapsed

        val appContext = context.applicationContext
        val prefs = try {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "讀取設定失敗: ${e.message}")
            null
        }
        val autoStartSpeedTrap = prefs?.getBoolean(KEY_AUTO_START_SPEEDTRAP, true) ?: true

        // 階段 0 (開機瞬間)：立即觸發基礎元件拉起
        performBootActions(appContext, autoStartSpeedTrap, isInitial = true)

        // 階段 1 (+4s)：車機 GPS 晶片剛通電，進行二次掛鉤與通知重綁
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            Log.i(TAG, "⚡ 開機 +4s 巡檢：校準 GPS 引擎與神盾通知連線")
            SpeedTrapListenerService.forceRebind(appContext)
            GpsSpeedManager.startListening(appContext)
            TopHudOverlayService.startIfEnabled(appContext)
        }, 4000L)

        // 階段 2 (+10s)：確保神盾常駐在背景與 GPS 鎖定
        handler.postDelayed({
            Log.i(TAG, "⚡ 開機 +10s 巡檢：確保神盾背景常駐與測速數據鎖定")
            SpeedTrapListenerService.forceRebind(appContext)
            GpsSpeedManager.startListening(appContext)
            if (autoStartSpeedTrap) {
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(appContext, bringHomeToFront = false, isBoot = true)
            }
        }, 10000L)

        // 階段 3 (+20s)：最終開機自癒確認
        handler.postDelayed({
            Log.i(TAG, "⚡ 開機 +20s 巡檢：最終確認 HUD 與測速引擎運行")
            SpeedTrapListenerService.forceRebind(appContext)
            GpsSpeedManager.startListening(appContext)
        }, 20000L)
    }

    private fun performBootActions(context: Context, autoStartSpeedTrap: Boolean, isInitial: Boolean) {
        // 1. 立即觸發 NotificationListener 連線 / 安全重綁
        try {
            SpeedTrapListenerService.forceRebind(context)
        } catch (e: Exception) {
            Log.e(TAG, "開機觸發 forceRebind 失敗: ${e.message}")
        }

        // 2. 自動啟動 GPS 車速引擎
        try {
            GpsSpeedManager.startListening(context)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 GPS 引擎失敗: ${e.message}")
        }

        // 3. 自動啟動全域置頂 HUD 懸浮球常駐 (保證上電立即可見)
        try {
            TopHudOverlayService.startIfEnabled(context)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 HUD 懸浮球失敗: ${e.message}")
        }

        // 4. 開機時安全檢查神盾測速
        if (autoStartSpeedTrap) {
            Log.i(TAG, "🚀 開機自啟：靜態確認神盾測速狀態...")
            try {
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = false, isBoot = isInitial)
            } catch (e: Exception) {
                Log.e(TAG, "開機喚醒神盾失敗: ${e.message}")
            }
        }
    }
}
