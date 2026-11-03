package com.carhud.speedtrap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast

class CarHudWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_LAUNCH_AA = "com.carhud.speedtrap.ACTION_LAUNCH_AA"
        const val ACTION_TOGGLE_WIFI = "com.carhud.speedtrap.ACTION_TOGGLE_WIFI"
        const val ACTION_CLOSE_HUD = "com.carhud.speedtrap.ACTION_CLOSE_HUD"

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

            // 多段動態測速與警示色判斷 (安全/前方相機/近距警戒/超速警戒)
            val isOverSpeed = speedLimit > 0 && currentSpeed > speedLimit
            val overDiff = if (isOverSpeed) currentSpeed - speedLimit else 0
            val isApproachingCam = distMeters > 0

            val speedColor: Int
            val camTextColor: Int
            val bgDrawableRes: Int

            when {
                // 1. 嚴重超速 (> 5 km/h) -> 鮮紅警戒
                overDiff > 5 -> {
                    speedColor = 0xFFEF4444.toInt() // 亮紅
                    camTextColor = 0xFFEF4444.toInt()
                    bgDrawableRes = R.drawable.bg_hud_top_bar_alert
                }
                // 2. 輕微超速 (1 ~ 5 km/h) -> 亮橘紅
                isOverSpeed -> {
                    speedColor = 0xFFF97316.toInt() // 亮橘
                    camTextColor = 0xFFF97316.toInt()
                    bgDrawableRes = R.drawable.bg_hud_top_bar_warning
                }
                // 3. 近距離相機警戒 (距離 <= 300m) -> 鮮明黃金時速與琥珀橘外框
                isApproachingCam && distMeters <= 300 -> {
                    speedColor = 0xFFFDE047.toInt() // 亮黃高亮
                    camTextColor = 0xFFF59E0B.toInt() // 琥珀橘
                    bgDrawableRes = R.drawable.bg_hud_top_bar_warning
                }
                // 4. 前方相機偵測 (> 300m) -> 金色相機外框與霓虹亮青時速
                isApproachingCam -> {
                    speedColor = 0xFF67E8F9.toInt() // 霓虹亮青
                    camTextColor = 0xFFFBBF24.toInt() // 金黃
                    bgDrawableRes = R.drawable.bg_hud_top_bar_cam
                }
                // 5. 平時路況順暢 -> 科技白與翠綠
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

            // 更新外框背景 (相機接近金光/超速紅光/琥珀橘)
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

            // 點擊關閉按鈕
            val closeIntent = Intent(context, CarHudWidgetProvider::class.java).apply {
                action = ACTION_CLOSE_HUD
            }
            val closePending = PendingIntent.getBroadcast(
                context, 103, closeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_close_hud, closePending)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 核心亮點 1：啟動原生 GPS 即時車速引擎 (每 0.5 秒即時刷新)
        GpsSpeedManager.startListening(context)

        // 核心亮點 2：自動確保全域置頂 HUD 懸浮球常駐 (避免開機閃爍與頻繁重開)
        TopHudOverlayService.startIfEnabled(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 啟動原生 GPS 車速
        GpsSpeedManager.startListening(context)

        // 桌面刷新時自動確保 HUD 懸浮球常駐
        TopHudOverlayService.startIfEnabled(context)

        val cur = SpeedTrapListenerService.latestData
        val speed = if (GpsSpeedManager.isGpsActive) GpsSpeedManager.currentGpsSpeed else if (GpsSpeedManager.currentGpsSpeed > 0) GpsSpeedManager.currentGpsSpeed else cur.currentSpeed
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
            ACTION_LAUNCH_AA -> QuickSwitchHelper.launchAndroidAuto(context)
            ACTION_TOGGLE_WIFI -> QuickSwitchHelper.toggleWifi(context)
            ACTION_CLOSE_HUD -> {
                val overlayIntent = Intent(context, TopHudOverlayService::class.java)
                try {
                    context.stopService(overlayIntent)
                    Toast.makeText(context, "已關閉置頂 HUD 懸浮球", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
