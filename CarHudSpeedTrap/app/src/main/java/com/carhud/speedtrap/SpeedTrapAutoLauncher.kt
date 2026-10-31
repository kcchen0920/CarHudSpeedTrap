package com.carhud.speedtrap

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

object SpeedTrapAutoLauncher {

    const val DEFAULT_SPEEDTRAP_PKG = "com.locallife.speedtrap"
    private const val TAG = "SpeedTrapAutoLauncher"
    private const val PREF_NAME = "carhud_prefs"
    const val KEY_CUSTOM_SPEEDTRAP_PKG = "custom_speedtrap_pkg"
    private const val KEY_LAST_LAUNCH_ELAPSED = "last_speedtrap_launch_elapsed"
    private const val KEY_LAST_LAUNCH_WALL_TIME = "last_speedtrap_launch_wall_time"

    // 自動喚醒冷卻時間：120 秒，徹底杜絕開機或連線時多個事件重複喚醒導致神盾異常報錯
    private const val AUTO_LAUNCH_COOLDOWN_MS = 120_000L

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
     * @param forceLaunch 是否強制啟動 (忽略背景心跳檢測與防抖冷卻)
     * @param isBoot 是否處於開機階段 (開機階段禁止頻繁跳轉桌面與避免異常斷言)
     */
    @Synchronized
    fun startSpeedTrapIfNeeded(
        context: Context,
        bringHomeToFront: Boolean = true,
        forceLaunch: Boolean = false,
        isBoot: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (!forceLaunch) {
            // 1. 若神盾已在背景正常運行（監聽器有收到心跳或作用中通知），絕不重複喚醒
            if (SpeedTrapListenerService.isSpeedTrapActive(context)) {
                Log.d(TAG, "神盾已在背景運行中 (通知活躍/心跳正常)，無需喚醒")
                return
            }

            // 2. 記憶體防抖防禦：冷卻期內不重複觸發
            if (now - lastLaunchAttemptTime < AUTO_LAUNCH_COOLDOWN_MS) {
                Log.d(TAG, "神盾剛嘗試啟動過 (記憶體冷卻中)，略過重複呼叫")
                return
            }

            // 3. SharedPreferences 跨處理序/跨接收器防抖防禦 (SystemClock 防止系統時間跳變)
            val lastElapsed = prefs.getLong(KEY_LAST_LAUNCH_ELAPSED, 0L)
            if (lastElapsed > 0 && (nowElapsed - lastElapsed) in 0 until AUTO_LAUNCH_COOLDOWN_MS) {
                val remainingSec = (AUTO_LAUNCH_COOLDOWN_MS - (nowElapsed - lastElapsed)) / 1000
                Log.d(TAG, "神盾啟動冷卻中 (剩餘 ${remainingSec} 秒)，略過重複喚醒")
                return
            }

            // 4. 進程備用檢測
            if (isSpeedTrapProcessRunning(context)) {
                Log.d(TAG, "神盾進程已存在，無需重複叫起")
                return
            }
        }

        lastLaunchAttemptTime = now
        prefs.edit()
            .putLong(KEY_LAST_LAUNCH_ELAPSED, nowElapsed)
            .putLong(KEY_LAST_LAUNCH_WALL_TIME, now)
            .apply()

        val targetPkg = findSpeedTrapPackage(context)

        if (targetPkg == null) {
            Log.w(TAG, "未偵測到神盾測速照相 App")
            if (!isBoot) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "未找到神盾 App，請至控制台「指定神盾應用」", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)

        if (launchIntent != null) {
            // 核心修正：使用 NEW_TASK 與 SINGLE_TOP，嚴禁使用 RESET_TASK_IF_NEEDED
            // RESET_TASK_IF_NEEDED 會強制重設神盾 Task Stack 導致 Activity 重複開啟與崩潰彈窗
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            
            try {
                context.startActivity(launchIntent)
                Log.i(TAG, "已平順喚醒神盾測速 App ($targetPkg)")
                if (!isBoot) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "正在啟動神盾測速照相...", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "啟動神盾 Activity 發生例外 (避免崩潰): ${t.message}")
                return
            }

            // 開機階段絕不執行強制壓回桌面 (避免與系統開機啟動競爭造成開關閃爍與 WindowManager assertion)
            if (bringHomeToFront && !isBoot) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(homeIntent)
                        Log.d(TAG, "已將主畫面切回桌面，神盾持續在背景常駐監控")
                    } catch (e: Exception) {
                        Log.w(TAG, "壓回桌面失敗: ${e.message}")
                    }
                }, 2800L)
            }
        }
    }

    private fun isSpeedTrapProcessRunning(context: Context): Boolean {
        return try {
            val targetPkg = findSpeedTrapPackage(context) ?: DEFAULT_SPEEDTRAP_PKG
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses ?: return false
            for (process in runningProcesses) {
                if (process.processName == targetPkg) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
