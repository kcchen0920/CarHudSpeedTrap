package com.carhud.speedtrap

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

object SpeedTrapAutoLauncher {

    const val DEFAULT_SPEEDTRAP_PKG = "com.locallife.speedtrap"
    private const val TAG = "SpeedTrapAutoLauncher"
    private const val PREF_NAME = "carhud_prefs"
    const val KEY_CUSTOM_SPEEDTRAP_PKG = "custom_speedtrap_pkg"

    private val KNOWN_SPEEDTRAP_PKGS = listOf(
        "com.locallife.speedtrap",
        "com.locallife.speedtrappro",
        "com.locallife.speedtrapfree",
        "com.speedtrap.android"
    )

    private var lastLaunchAttemptTime: Long = 0L

    /**
     * 尋找車機上安裝的神盾 App 套件名
     */
    fun findSpeedTrapPackage(context: Context): String? {
        val pm = context.packageManager
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 1. 優先使用手動指定的自訂包名
        val customPkg = prefs.getString(KEY_CUSTOM_SPEEDTRAP_PKG, null)
        if (!customPkg.isNullOrEmpty()) {
            if (pm.getLaunchIntentForPackage(customPkg) != null) {
                return customPkg
            }
        }

        // 2. 檢查已知神盾包名
        for (pkg in KNOWN_SPEEDTRAP_PKGS) {
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                return pkg
            }
        }

        // 3. 智慧關鍵字模糊搜尋：尋找應用名稱含有「神盾」或包名含有「speedtrap」的 App
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                if (label.contains("神盾") || pkg.contains("speedtrap", ignoreCase = true) || pkg.contains("locallife", ignoreCase = true)) {
                    if (pm.getLaunchIntentForPackage(pkg) != null) {
                        Log.i(TAG, "自動模糊匹配到神盾 App: $label ($pkg)")
                        prefs.edit().putString(KEY_CUSTOM_SPEEDTRAP_PKG, pkg).apply()
                        return pkg
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜尋已安裝應用失敗: ${e.message}")
        }

        return null
    }

    /**
     * 檢查並自動喚醒神盾 App
     * @param bringHomeToFront 啟動後是否將畫面切回桌面 (避免神盾大視窗蓋住車機介面)
     * @param forceLaunch 是否強制啟動 (忽略背景心跳檢測)
     */
    fun startSpeedTrapIfNeeded(context: Context, bringHomeToFront: Boolean = true, forceLaunch: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceLaunch) {
            if (now - lastLaunchAttemptTime < 8000L) {
                return
            }
            if (isSpeedTrapRunning(context)) {
                Log.d(TAG, "神盾已在背景運行中，無需重複喚醒")
                return
            }
        }

        lastLaunchAttemptTime = now
        val targetPkg = findSpeedTrapPackage(context)

        if (targetPkg == null) {
            Log.w(TAG, "未偵測到神盾測速照相 App")
            Toast.makeText(context, "未找到神盾 App，請至控制台「指定神盾應用」", Toast.LENGTH_LONG).show()
            return
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            Log.d(TAG, "已自動喚醒神盾測速 App ($targetPkg)")
            Toast.makeText(context, "正在啟動神盾測速照相...", Toast.LENGTH_SHORT).show()

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
        }
    }

    private fun isSpeedTrapRunning(context: Context): Boolean {
        // 1. 優先檢查通知心跳：若 25 秒內曾收到神盾廣播，代表百分之百正在背景運行
        val lastNotif = SpeedTrapListenerService.lastNotificationTimeMillis
        if (lastNotif > 0 && (System.currentTimeMillis() - lastNotif) < 25000L) {
            return true
        }

        // 2. 備用進程檢查
        val targetPkg = findSpeedTrapPackage(context) ?: DEFAULT_SPEEDTRAP_PKG
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = am.runningAppProcesses ?: return false
        for (process in runningProcesses) {
            if (process.processName == targetPkg) {
                return true
            }
        }
        return false
    }
}
