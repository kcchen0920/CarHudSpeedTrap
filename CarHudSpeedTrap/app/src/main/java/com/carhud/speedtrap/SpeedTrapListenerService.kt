package com.carhud.speedtrap

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.regex.Pattern

data class SpeedTrapData(
    val speedLimit: Int,
    val distanceMeters: Int,
    val currentSpeed: Int,
    val camType: String
)

class SpeedTrapListenerService : NotificationListenerService() {

    companion object {
        const val SPEEDTRAP_PACKAGE = "com.locallife.speedtrap"

        // 記錄最近一次接收到通知的時間戳記（供保活與運行偵測判定）
        @Volatile
        var lastNotificationTimeMillis: Long = 0L

        // 最新快取的測速資料
        @Volatile
        var latestData: SpeedTrapData = SpeedTrapData(
            speedLimit = 60,
            distanceMeters = 0,
            currentSpeed = 0,
            camType = "路況順暢"
        )

        // 供 Activity / Overlay 註冊回呼
        var onDataUpdated: ((SpeedTrapData) -> Unit)? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // 僅處理神盾測速通知
        if (sbn.packageName != SPEEDTRAP_PACKAGE) return

        lastNotificationTimeMillis = System.currentTimeMillis()

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

        val rawContent = "$title $text $subText"
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

        // 2. 觸發即時記憶體回呼 (若全域置頂 Overlay 或 Activity 開啟中)
        onDataUpdated?.invoke(data)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null || sbn.packageName != SPEEDTRAP_PACKAGE) return

        // 檢查神盾是否還有其他作用中的通知
        val hasActiveSpeedTrap = try {
            activeNotifications?.any { it.packageName == SPEEDTRAP_PACKAGE } ?: false
        } catch (e: Exception) {
            false
        }

        if (!hasActiveSpeedTrap) {
            // 駛離測速區間或無相機通知：自動重設為「路況順暢」清空倒數進度
            val clearedData = SpeedTrapData(
                speedLimit = if (latestData.speedLimit > 0) latestData.speedLimit else 60,
                distanceMeters = 0,
                currentSpeed = latestData.currentSpeed,
                camType = "路況順暢"
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
        }
    }

    private fun parseNotificationContent(content: String): SpeedTrapData {
        var limit = 0
        var dist = 0
        var speed = 0
        var camType = "測速照相"

        try {
            // 匹配相機類型 (科技執法 / 區間 / 流動 / 闖紅燈 / 固定)
            if (content.contains("科技執法")) camType = "科技執法"
            else if (content.contains("區間")) camType = "區間測速"
            else if (content.contains("流動")) camType = "流動測速"
            else if (content.contains("闖紅燈")) camType = "闖紅燈照相"
            else if (content.contains("固定")) camType = "固定測速"

            // 匹配限速 (例如: "限速 60", "速限: 50")
            val limitMatcher = Pattern.compile("(限速|速限)\\s*[:：]?\\s*(\\d+)").matcher(content)
            if (limitMatcher.find()) {
                limit = limitMatcher.group(2)?.toIntOrNull() ?: 0
            }

            // 匹配距離 (例如: "350公尺", "500m", "距離 200 公尺")
            val distMatcher = Pattern.compile("(\\d+)\\s*(公尺|m)", Pattern.CASE_INSENSITIVE).matcher(content)
            if (distMatcher.find()) {
                dist = distMatcher.group(1)?.toIntOrNull() ?: 0
            }

            // 匹配目前車速 (例如: "時速 55", "速度 60", "目前: 70")
            val speedMatcher = Pattern.compile("(時速|速度|目前)\\s*[:：]?\\s*(\\d+)").matcher(content)
            if (speedMatcher.find()) {
                speed = speedMatcher.group(2)?.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return SpeedTrapData(
            speedLimit = if (limit > 0) limit else 60,
            distanceMeters = dist,
            currentSpeed = speed,
            camType = camType
        )
    }
}
