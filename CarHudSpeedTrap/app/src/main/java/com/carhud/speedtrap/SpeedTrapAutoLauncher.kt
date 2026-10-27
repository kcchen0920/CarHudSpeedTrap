package com.carhud.speedtrap

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

object SpeedTrapAutoLauncher {

    const val SPEEDTRAP_PKG = "com.locallife.speedtrap"
    private const val TAG = "SpeedTrapAutoLauncher"

    private var lastLaunchAttemptTime: Long = 0L

    /**
     * 檢查並自動喚醒神盾 App
     * @param bringHomeToFront 啟動後是否將畫面切回桌面 (避免神盾大視窗蓋住車機介面)
     */
    fun startSpeedTrapIfNeeded(context: Context, bringHomeToFront: Boolean = true) {
        val now = System.currentTimeMillis()
        // 避免 10 秒內重複喚醒
        if (now - lastLaunchAttemptTime < 10000L) {
            return
        }

        if (isSpeedTrapRunning(context)) {
            Log.d(TAG, "神盾已在背景運行中，無需重複喚醒")
            return
        }

        lastLaunchAttemptTime = now
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(SPEEDTRAP_PKG)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            Log.d(TAG, "已自動喚醒神盾測速 App")

            // 延遲 1.2 秒將畫面壓回 CarWebGuru 桌面 (保持背景常駐而不搶焦點)
            if (bringHomeToFront) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(homeIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, 1200)
            }
        } else {
            Log.w(TAG, "尚未安裝神盾測速照相 App ($SPEEDTRAP_PKG)")
        }
    }

    private fun isSpeedTrapRunning(context: Context): Boolean {
        // 1. 優先檢查通知心跳：若 25 秒內曾收到神盾廣播，代表百分之百正在背景運行
        val lastNotif = SpeedTrapListenerService.lastNotificationTimeMillis
        if (lastNotif > 0 && (System.currentTimeMillis() - lastNotif) < 25000L) {
            return true
        }

        // 2. 備用進程檢查 (舊版本 Android 或 Root 系統可用)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = am.runningAppProcesses ?: return false
        for (process in runningProcesses) {
            if (process.processName == SPEEDTRAP_PKG) {
                return true
            }
        }
        return false
    }
}
