package com.carhud.speedtrap

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class SpeedTrapData(
    val speedLimit: Int,
    val distanceMeters: Int,
    val currentSpeed: Int,
    val camType: String,
    val rawText: String = ""
)

class SpeedTrapListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "SpeedTrapListener"
        const val SPEEDTRAP_PACKAGE = "com.locallife.speedtrap"

        // 服務是否成功連線至系統
        @Volatile
        var isConnected: Boolean = false

        // 記錄最近一次接收到通知的時間戳記（供保活與運行偵測判定）
        @Volatile
        var lastNotificationTimeMillis: Long = 0L

        // 記錄最近一次接收到的除錯資訊
        @Volatile
        var lastNotificationTime: String = "尚未收到任何通知"
        @Volatile
        var lastPackageName: String = "無"
        @Volatile
        var lastRawText: String = "無"

        // 最新快取的測速資料
        @Volatile
        var latestData: SpeedTrapData = SpeedTrapData(
            speedLimit = 60,
            distanceMeters = 0,
            currentSpeed = 0,
            camType = "🟢 服務就緒，等待測速訊號"
        )

        // 供 Activity / Overlay 註冊回呼
        var onDataUpdated: ((SpeedTrapData) -> Unit)? = null
        var onDebugInfoUpdated: (() -> Unit)? = null

        /**
         * 強制重新連線 NotificationListenerService
         */
        fun forceRebind(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val comp = ComponentName(context, SpeedTrapListenerService::class.java)
                    // 切換 Component 啟用狀態可強迫 Android 重新綁定 Listener
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "已觸發 NotificationListener 強制重連")
                }
            } catch (e: Exception) {
                Log.e(TAG, "forceRebind 失敗: ${e.message}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "✓ NotificationListenerService 成功連線！")
        onDebugInfoUpdated?.invoke()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "⚠ NotificationListenerService 已斷線！")
        onDebugInfoUpdated?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, SpeedTrapListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: ""

        // 寬鬆判定：只要是神盾（包含大小寫、變體、locallife 等）即處理
        val isSpeedTrapApp = pkgName.contains("speedtrap", ignoreCase = true) ||
                pkgName.contains("locallife", ignoreCase = true) ||
                pkgName == SPEEDTRAP_PACKAGE

        if (!isSpeedTrapApp) return

        lastNotificationTimeMillis = System.currentTimeMillis()

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lastNotificationTime = timeFormat.format(Date())
        lastPackageName = pkgName

        // 徹底解決 getString() 取出 null 的 bug：全部使用 getCharSequence()
        val extras = sbn.notification.extras
        val contentBuilder = StringBuilder()

        if (extras != null) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""

            contentBuilder.append(title).append(" ")
                .append(text).append(" ")
                .append(subText).append(" ")
                .append(bigText).append(" ")
                .append(infoText).append(" ")
                .append(summaryText).append(" ")

            // 遍歷所有 extras 欄位防禦特殊自訂 key
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is CharSequence && value.isNotEmpty()) {
                    contentBuilder.append(value).append(" ")
                }
            }
        }

        val ticker = sbn.notification.tickerText?.toString() ?: ""
        if (ticker.isNotEmpty()) {
            contentBuilder.append(ticker).append(" ")
        }

        val rawContent = contentBuilder.toString().trim()
        lastRawText = if (rawContent.isNotEmpty()) rawContent else "(通知內文為空)"

        val data = parseNotificationContent(rawContent)
        latestData = data

        // 1. 推送更新給 CarWebGuru AppWidget
        CarHudWidgetProvider.updateWidgetData(
            context = this,
            speedLimit = data.speedLimit,
            distMeters = data.distanceMeters,
            currentSpeed = data.currentSpeed,
            camType = data.camType
        )

        // 2. 觸發回呼
        onDataUpdated?.invoke(data)
        onDebugInfoUpdated?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: ""
        val isSpeedTrapApp = pkgName.contains("speedtrap", ignoreCase = true) ||
                pkgName.contains("locallife", ignoreCase = true)

        if (!isSpeedTrapApp) return

        lastNotificationTimeMillis = System.currentTimeMillis()

        // 檢查神盾是否還有其他作用中的通知
        val hasActiveSpeedTrap = try {
            activeNotifications?.any {
                it.packageName.contains("speedtrap", ignoreCase = true) ||
                        it.packageName.contains("locallife", ignoreCase = true)
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (!hasActiveSpeedTrap) {
            val clearedData = SpeedTrapData(
                speedLimit = if (latestData.speedLimit > 0) latestData.speedLimit else 60,
                distanceMeters = 0,
                currentSpeed = latestData.currentSpeed,
                camType = "🟢 路況順暢"
            )
            latestData = clearedData

            CarHudWidgetProvider.updateWidgetData(
                context = this,
                speedLimit = clearedData.speedLimit,
                distMeters = 0,
                currentSpeed = clearedData.currentSpeed,
                camType = "🟢 路況順暢"
            )
            onDataUpdated?.invoke(clearedData)
            onDebugInfoUpdated?.invoke()
        }
    }

    private fun parseNotificationContent(content: String): SpeedTrapData {
        var limit = 0
        var dist = 0
        var speed = 0
        var camType = "測速照相"

        try {
            // 匹配相機類型
            if (content.contains("科技執法")) camType = "科技執法"
            else if (content.contains("區間")) camType = "區間測速"
            else if (content.contains("流動")) camType = "流動測速"
            else if (content.contains("闖紅燈")) camType = "闖紅燈照相"
            else if (content.contains("固定")) camType = "固定測速"

            // 匹配限速 (支援: "限速 60", "速限:50", "限制時速 110", "限速60公里")
            val limitMatcher = Pattern.compile("(限速|速限|限制時速|速限值)\\s*[:：=]?\\s*(\\d+)").matcher(content)
            if (limitMatcher.find()) {
                limit = limitMatcher.group(2)?.toIntOrNull() ?: 0
            }

            // 匹配距離 (支援公里轉換與公尺: "1.2公里", "500公尺", "350m", "500 米")
            val kmMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|km)", Pattern.CASE_INSENSITIVE).matcher(content)
            if (kmMatcher.find()) {
                val km = kmMatcher.group(1)?.toFloatOrNull() ?: 0f
                dist = (km * 1000).toInt()
            } else {
                val distMatcher = Pattern.compile("(\\d+)\\s*(公尺|m|米)", Pattern.CASE_INSENSITIVE).matcher(content)
                if (distMatcher.find()) {
                    dist = distMatcher.group(1)?.toIntOrNull() ?: 0
                }
            }

            // 匹配目前車速 (支援: "時速 55", "速度 60", "目前: 70", "55 km/h")
            val speedMatcher = Pattern.compile("(時速|速度|目前時速|目前速度|車速)\\s*[:：=]?\\s*(\\d+)").matcher(content)
            if (speedMatcher.find()) {
                speed = speedMatcher.group(2)?.toIntOrNull() ?: 0
            } else {
                val kmhMatcher = Pattern.compile("(\\d+)\\s*km/?h", Pattern.CASE_INSENSITIVE).matcher(content)
                if (kmhMatcher.find()) {
                    speed = kmhMatcher.group(1)?.toIntOrNull() ?: 0
                }
            }

            // 若完全沒出現測速距離，但神盾在背景常駐
            if (dist == 0 && (content.contains("背景") || content.contains("常駐") || content.contains("運行") || content.contains("定位"))) {
                camType = "🟢 神盾監控中"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return SpeedTrapData(
            speedLimit = if (limit > 0) limit else (if (latestData.speedLimit > 0) latestData.speedLimit else 60),
            distanceMeters = dist,
            currentSpeed = speed,
            camType = camType,
            rawText = content
        )
    }
}
