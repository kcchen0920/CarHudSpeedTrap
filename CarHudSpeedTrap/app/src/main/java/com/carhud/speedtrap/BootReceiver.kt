package com.carhud.speedtrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 車機開機與喚醒廣播接收器
 * 攔截系統開機完成、快速開機、解鎖及更新完成事件，全自動啟動神盾測速與 HUD 懸浮條
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBootReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_START_HUD = "auto_start_hud"
        const val KEY_AUTO_START_SPEEDTRAP = "auto_start_speedtrap"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "⚡ 收到開機/喚醒事件廣播: $action")

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val autoStartHud = prefs.getBoolean(KEY_AUTO_START_HUD, true)
        val autoStartSpeedTrap = prefs.getBoolean(KEY_AUTO_START_SPEEDTRAP, true)

        // 1. 自動啟動 GPS 車速引擎
        try {
            GpsSpeedManager.startListening(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "開機啟動 GPS 引擎失敗: ${e.message}")
        }

        // 2. 自動在背景喚醒神盾測速
        if (autoStartSpeedTrap) {
            Log.i(TAG, "🚀 開機自啟：正在自動喚醒神盾測速 App...")
            SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)
        }

        // 3. 自動啟動全域置頂 HUD 懸浮條 (若具備懸浮窗權限)
        if (autoStartHud) {
            val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }

            if (canDraw) {
                Log.i(TAG, "🚗 開機自啟：正在啟動 TopHudOverlayService 懸浮條...")
                val overlayIntent = Intent(context, TopHudOverlayService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, overlayIntent)
                    } else {
                        context.startService(overlayIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "開機啟動 TopHudOverlayService 失敗: ${e.message}")
                }
            } else {
                Log.w(TAG, "尚未獲取懸浮窗權限，略過懸浮條自啟")
            }
        }
    }
}
