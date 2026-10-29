package com.carhud.speedtrap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat

class TopHudOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        initOverlay()
        // 核心：懸浮條常駐服務啟動時，立即啟動高精度 GPS 測速引擎
        GpsSpeedManager.startListening(this)
    }

    private fun startForegroundNotification() {
        val channelId = "hud_overlay_channel"
        val channelName = "車機 HUD 懸浮服務"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 HUD 全域置頂懸浮橫條在背景穩定常駐"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("車機 HUD 服務常駐中")
            .setContentText("即時測速與懸浮監控運作中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    private fun initOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.widget_top_hud, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density).toInt(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // 綁定點擊事件
        overlayView?.findViewById<View>(R.id.btn_launch_aa)?.setOnClickListener {
            QuickSwitchHelper.launchAndroidAuto(this)
        }
        overlayView?.findViewById<View>(R.id.btn_toggle_wifi)?.setOnClickListener {
            QuickSwitchHelper.toggleWifi(this)
        }

        windowManager?.addView(overlayView, params)

        // 載入當前快取數據
        updateOverlayUi(SpeedTrapListenerService.latestData)

        // 監聽神盾資料更新回呼
        SpeedTrapListenerService.onDataUpdated = { data ->
            updateOverlayUi(data)
        }
    }

    private fun updateOverlayUi(data: SpeedTrapData) {
        overlayView?.post {
            val tvLimit = overlayView?.findViewById<TextView>(R.id.tv_speed_limit)
            val tvCam = overlayView?.findViewById<TextView>(R.id.tv_cam_info)
            val tvSpeed = overlayView?.findViewById<TextView>(R.id.tv_current_speed)
            val pbDist = overlayView?.findViewById<ProgressBar>(R.id.pb_distance)

            tvLimit?.text = if (data.speedLimit > 0) data.speedLimit.toString() else "--"
            tvSpeed?.text = data.currentSpeed.toString()
            pbDist?.progress = data.distanceMeters.coerceIn(0, 1000)

            val isOverSpeed = data.speedLimit > 0 && data.currentSpeed > data.speedLimit
            val overDiff = if (isOverSpeed) data.currentSpeed - data.speedLimit else 0

            when {
                overDiff > 5 -> {
                    tvSpeed?.setTextColor(0xFFEF4444.toInt())
                    tvCam?.setTextColor(0xFFEF4444.toInt())
                    overlayView?.setBackgroundResource(R.drawable.bg_hud_top_bar_alert)
                    tvCam?.text = "⚠️ 超速警戒！${data.camType} ${data.distanceMeters}m"
                }
                isOverSpeed -> {
                    tvSpeed?.setTextColor(0xFFF59E0B.toInt())
                    tvCam?.setTextColor(0xFFF59E0B.toInt())
                    overlayView?.setBackgroundResource(R.drawable.bg_hud_top_bar_warning)
                    tvCam?.text = "⚠️ 請注意車速！${data.camType} ${data.distanceMeters}m"
                }
                data.distanceMeters > 0 -> {
                    tvSpeed?.setTextColor(0xFFFFFFFF.toInt())
                    tvCam?.setTextColor(if (data.distanceMeters < 300) 0xFFF59E0B.toInt() else 0xFF00F2FE.toInt())
                    overlayView?.setBackgroundResource(R.drawable.bg_hud_top_bar)
                    tvCam?.text = "📷 ${data.camType} ${data.distanceMeters}m"
                }
                else -> {
                    tvSpeed?.setTextColor(0xFFFFFFFF.toInt())
                    tvCam?.setTextColor(0xFF10B981.toInt())
                    overlayView?.setBackgroundResource(R.drawable.bg_hud_top_bar)
                    tvCam?.text = "🟢 路況順暢"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SpeedTrapListenerService.onDataUpdated = null
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
        }
    }
}
