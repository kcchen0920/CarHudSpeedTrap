package com.carhud.speedtrap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class CarHudWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_LAUNCH_AA = "com.carhud.speedtrap.ACTION_LAUNCH_AA"
        const val ACTION_TOGGLE_WIFI = "com.carhud.speedtrap.ACTION_TOGGLE_WIFI"

        /**
         * 供外部 Service 更新 Widget 內容
         */
        fun updateWidgetData(
            context: Context,
            speedLimit: Int,
            distMeters: Int,
            currentSpeed: Int,
            camType: String = "固定測速"
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, CarHudWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isEmpty()) return

            val remoteViews = RemoteViews(context.packageName, R.layout.widget_top_hud)

            // 更新速限數值
            remoteViews.setTextViewText(R.id.tv_speed_limit, if (speedLimit > 0) speedLimit.toString() else "--")

            // 三段動態警示色判斷 (安全/警告/嚴重超速)
            val isOverSpeed = speedLimit > 0 && currentSpeed > speedLimit
            val overDiff = if (isOverSpeed) currentSpeed - speedLimit else 0

            val speedColor: Int
            val camTextColor: Int
            val bgDrawableRes: Int

            when {
                // 1. 嚴重超速 (> 5 km/h)
                overDiff > 5 -> {
                    speedColor = 0xFFEF4444.toInt() // 亮紅
                    camTextColor = 0xFFEF4444.toInt()
                    bgDrawableRes = R.drawable.bg_hud_top_bar_alert
                }
                // 2. 輕微超速 (1 ~ 5 km/h)
                isOverSpeed -> {
                    speedColor = 0xFFF59E0B.toInt() // 琥珀橘
                    camTextColor = 0xFFF59E0B.toInt()
                    bgDrawableRes = R.drawable.bg_hud_top_bar_warning
                }
                // 3. 測速區間接近中 (未超速)
                distMeters > 0 -> {
                    speedColor = 0xFFFFFFFF.toInt()
                    camTextColor = if (distMeters < 300) 0xFFF59E0B.toInt() else 0xFF00F2FE.toInt()
                    bgDrawableRes = R.drawable.bg_hud_top_bar
                }
                // 4. 路況順暢
                else -> {
                    speedColor = 0xFFFFFFFF.toInt()
                    camTextColor = 0xFF10B981.toInt() // 翠綠
                    bgDrawableRes = R.drawable.bg_hud_top_bar
                }
            }

            // 更新相機文字與倒數距離
            val camText = if (distMeters > 0) "📷 $camType ${distMeters}m" else (if (camType.isNotEmpty()) camType else "🟢 路況順暢")
            remoteViews.setTextViewText(R.id.tv_cam_info, camText)
            remoteViews.setTextColor(R.id.tv_cam_info, camTextColor)

            // 更新時速文字與顏色
            remoteViews.setTextViewText(R.id.tv_current_speed, currentSpeed.toString())
            remoteViews.setTextColor(R.id.tv_current_speed, speedColor)

            // 更新外框背景 (超速時外框紅光/琥珀光)
            remoteViews.setInt(R.id.widget_root, "setBackgroundResource", bgDrawableRes)

            // 更新距離進度條 (上限設定為 1000m)
            remoteViews.setProgressBar(R.id.pb_distance, 1000, distMeters.coerceIn(0, 1000), false)

            // 綁定點擊事件
            bindClickIntents(context, remoteViews)

            appWidgetManager.updateAppWidget(thisWidget, remoteViews)
        }

        private fun bindClickIntents(context: Context, views: RemoteViews) {
            // 點擊 AA 按鈕
            val aaIntent = Intent(context, CarHudWidgetProvider::class.java).apply {
                action = ACTION_LAUNCH_AA
            }
            val aaPending = PendingIntent.getBroadcast(
                context, 101, aaIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_launch_aa, aaPending)

            // 點擊 Wi-Fi 按鈕
            val wifiIntent = Intent(context, CarHudWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_WIFI
            }
            val wifiPending = PendingIntent.getBroadcast(
                context, 102, wifiIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_toggle_wifi, wifiPending)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 核心亮點 1：啟動原生 GPS 即時車速引擎 (每 0.5 秒即時刷新)
        GpsSpeedManager.startListening(context)

        // 核心亮點 2：CarWebGuru 載入 Widget 時，自動喚醒神盾 App，免手動點擊
        SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 啟動原生 GPS 車速
        GpsSpeedManager.startListening(context)

        // 每次桌面刷新時確保神盾在運行
        SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)

        val cur = SpeedTrapListenerService.latestData
        val speed = if (GpsSpeedManager.currentGpsSpeed > 0) GpsSpeedManager.currentGpsSpeed else cur.currentSpeed
        updateWidgetData(
            context = context,
            speedLimit = cur.speedLimit,
            distMeters = cur.distanceMeters,
            currentSpeed = speed,
            camType = cur.camType
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // 開機自動喚醒神盾
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)
            }
            ACTION_LAUNCH_AA -> QuickSwitchHelper.launchAndroidAuto(context)
            ACTION_TOGGLE_WIFI -> QuickSwitchHelper.toggleWifi(context)
        }
    }
}
