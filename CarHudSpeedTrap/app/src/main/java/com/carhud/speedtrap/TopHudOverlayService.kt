package com.carhud.speedtrap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 全域置頂小型圓形 HUD 懸浮球服務
 * 常駐於螢幕右上角，預設直徑約 66dp，高通透半透明設計，絕不遮擋導航地圖與前台 App
 * 支援拖曳自訂位置、點擊展開快捷功能卡、放大/縮小自訂尺寸、5秒無操作自動折疊
 */
class TopHudOverlayService : Service() {

    companion object {
        private const val TAG = "TopHudOverlayService"
        const val PREF_HUD_ALPHA = "hud_alpha"
        const val DEFAULT_HUD_ALPHA = 0.72f // 預設高透光度 72%

        const val PREF_HUD_SCALE = "hud_scale"
        const val DEFAULT_HUD_SCALE = 1.0f // 預設 100% 縮放比例

        var isRunning: Boolean = false
        var currentService: TopHudOverlayService? = null
        var currentScale: Float = DEFAULT_HUD_SCALE

        /**
         * 供外部更新透明度
         */
        fun updateAlpha(alpha: Float) {
            currentService?.apply {
                overlayParams?.alpha = alpha
                if (overlayView != null && overlayParams != null && windowManager != null) {
                    try {
                        windowManager?.updateViewLayout(overlayView, overlayParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        /**
         * 供外部更新圖示大小縮放
         */
        fun updateScale(scale: Float) {
            currentService?.applyScale(scale)
        }

        /**
         * 全域安全自啟輔助函式：若具備懸浮窗權限且尚未啟動，自動開啟全域置頂 HUD 懸浮球常駐
         */
        fun startIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences("carhud_prefs", Context.MODE_PRIVATE)
            val autoStartHud = prefs.getBoolean(BootReceiver.KEY_AUTO_START_HUD, true)
            if (!autoStartHud) return

            val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
            if (!canDraw) {
                Log.w(TAG, "未獲取懸浮窗權限，無法自啟 HUD 懸浮球")
                return
            }

            if (isRunning && currentService != null) {
                return
            }

            val overlayIntent = Intent(context, TopHudOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
                Log.i(TAG, "✓ 已成功觸發 TopHudOverlayService 懸浮球常駐啟動")
            } catch (e: Exception) {
                Log.w(TAG, "啟動 TopHudOverlayService 失敗: ${e.message}")
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var layoutExpandedMenu: View? = null
    private var circleBadge: View? = null
    private var tvCircleSpeed: TextView? = null
    private var tvCircleSub: TextView? = null
    private var tvCircleSpeedLimit: TextView? = null
    private var tvScaleLabel: TextView? = null

    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        layoutExpandedMenu?.visibility = View.GONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        currentService = this
        startForegroundNotification()
        initOverlay()
        // 懸浮服務啟動時，立即啟動高精度 GPS 測速引擎
        GpsSpeedManager.startListening(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_STICKY
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
                description = "保持右上角 HUD 懸浮球在背景穩定常駐"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("車機 HUD 懸浮球運作中")
            .setContentText("即時測速與相機監控運作中 (常駐於螢幕右上角)")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    private fun initOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_hud_circle, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val prefs = getSharedPreferences("carhud_prefs", Context.MODE_PRIVATE)
        val hudAlpha = prefs.getFloat(PREF_HUD_ALPHA, DEFAULT_HUD_ALPHA)
        currentScale = prefs.getFloat(PREF_HUD_SCALE, DEFAULT_HUD_SCALE)

        val dm = resources.displayMetrics
        // 初始位置設定在右上角，預留邊距
        val defaultX = dm.widthPixels - (84 * dm.density).toInt()
        val defaultY = (24 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultX.coerceAtLeast(0)
            y = defaultY.coerceAtLeast(0)
            alpha = hudAlpha // 透明度設定，確保底層地圖/導航清透可視
        }
        overlayParams = params

        circleBadge = overlayView?.findViewById(R.id.circle_badge)
        layoutExpandedMenu = overlayView?.findViewById(R.id.layout_expanded_menu)
        tvCircleSpeed = overlayView?.findViewById(R.id.tv_circle_speed)
        tvCircleSub = overlayView?.findViewById(R.id.tv_circle_sub)
        tvCircleSpeedLimit = overlayView?.findViewById(R.id.tv_circle_speed_limit)
        tvScaleLabel = overlayView?.findViewById(R.id.tv_circle_scale_label)

        // 套用儲存的縮放比例
        applyScale(currentScale, savePref = false)

        // 綁定觸控拖曳與點擊切換
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialParamX = 0
        var initialParamY = 0
        var isDragging = false
        val touchSlop = 12 * dm.density

        circleBadge?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialParamX = params.x
                    initialParamY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialParamX + dx).coerceIn(0, dm.widthPixels - v.width)
                        params.y = (initialParamY + dy).coerceIn(0, dm.heightPixels - v.height)
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpandedMenu()
                    }
                    true
                }
                else -> false
            }
        }

        // 展開選單中的功能操作按鈕
        overlayView?.findViewById<View>(R.id.btn_circle_aa)?.setOnClickListener {
            QuickSwitchHelper.launchAndroidAuto(this)
            autoCollapseHandler.postDelayed(autoCollapseRunnable, 1000L)
        }
        overlayView?.findViewById<View>(R.id.btn_circle_wifi)?.setOnClickListener {
            QuickSwitchHelper.toggleWifi(this)
            autoCollapseHandler.postDelayed(autoCollapseRunnable, 1000L)
        }
        overlayView?.findViewById<View>(R.id.btn_circle_close)?.setOnClickListener {
            Toast.makeText(this, "已關閉右上角 HUD 懸浮球", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        // 放大與縮小圖示按鈕 (調整區間 70% ~ 160%)
        overlayView?.findViewById<View>(R.id.btn_circle_zoom_in)?.setOnClickListener {
            val newScale = ((currentScale + 0.15f) * 100).roundToInt() / 100f
            applyScale(newScale.coerceAtMost(1.60f))
            resetAutoCollapseTimer()
        }
        overlayView?.findViewById<View>(R.id.btn_circle_zoom_out)?.setOnClickListener {
            val newScale = ((currentScale - 0.15f) * 100).roundToInt() / 100f
            applyScale(newScale.coerceAtLeast(0.70f))
            resetAutoCollapseTimer()
        }

        windowManager?.addView(overlayView, params)

        // 載入當前測速快取數據
        updateOverlayUi(SpeedTrapListenerService.latestData)

        // 監聽神盾通知與 GPS 車速回呼
        SpeedTrapListenerService.onDataUpdated = { data ->
            updateOverlayUi(data)
        }

        GpsSpeedManager.onGpsSpeedUpdated = { _, _ ->
            updateOverlayUi(SpeedTrapListenerService.latestData)
        }
    }

    /**
     * 動態套用圖示大小縮放 (以 66dp 為 100% 基準)
     */
    fun applyScale(scale: Float, savePref: Boolean = true) {
        currentScale = scale
        val dm = resources.displayMetrics
        val baseBadgeDp = 66f
        val newBadgeSizePx = (baseBadgeDp * dm.density * scale).toInt()

        circleBadge?.let { badge ->
            val lp = badge.layoutParams
            lp.width = newBadgeSizePx
            lp.height = newBadgeSizePx
            badge.layoutParams = lp
        }

        // 動態縮放字型與速限小圈圈
        tvCircleSpeed?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f * scale)
        tvCircleSub?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scale)

        tvCircleSpeedLimit?.let { limitView ->
            val limitLp = limitView.layoutParams
            val newLimitSizePx = (22f * dm.density * scale).toInt()
            limitLp.width = newLimitSizePx
            limitLp.height = newLimitSizePx
            limitView.layoutParams = limitLp
            limitView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
        }

        val percent = (scale * 100).roundToInt()
        tvScaleLabel?.text = "$percent%"

        if (overlayView != null && overlayParams != null && windowManager != null) {
            try {
                windowManager?.updateViewLayout(overlayView, overlayParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (savePref) {
            getSharedPreferences("carhud_prefs", Context.MODE_PRIVATE)
                .edit().putFloat(PREF_HUD_SCALE, scale).apply()
        }
    }

    private fun resetAutoCollapseTimer() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        autoCollapseHandler.postDelayed(autoCollapseRunnable, 5000L)
    }

    private fun toggleExpandedMenu() {
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        if (layoutExpandedMenu?.visibility == View.VISIBLE) {
            layoutExpandedMenu?.visibility = View.GONE
        } else {
            layoutExpandedMenu?.visibility = View.VISIBLE
            // 展開 5 秒後自動折疊，避免長時間佔據空間
            autoCollapseHandler.postDelayed(autoCollapseRunnable, 5000L)
        }
    }

    private fun updateOverlayUi(data: SpeedTrapData) {
        overlayView?.post {
            val tvLimit = tvCircleSpeedLimit
            val tvSpeed = tvCircleSpeed
            val tvSub = tvCircleSub
            val badge = circleBadge
            val tvExpandedInfo = overlayView?.findViewById<TextView>(R.id.tv_expanded_info)

            // 即時車速優先採用 GPS 速度
            val currentSpeed = if (GpsSpeedManager.currentGpsSpeed > 0) GpsSpeedManager.currentGpsSpeed else data.currentSpeed
            tvSpeed?.text = currentSpeed.toString()

            // 速限圓圈：有速限時顯示紅外框數字，無速限時隱藏
            if (data.speedLimit > 0) {
                tvLimit?.text = data.speedLimit.toString()
                tvLimit?.visibility = View.VISIBLE
            } else {
                tvLimit?.visibility = View.GONE
            }

            // 底部文字：有相機距離且 > 0 顯示倒數米數，否則顯示 km/h
            if (data.distanceMeters > 0) {
                tvSub?.text = "${data.distanceMeters}m"
            } else {
                tvSub?.text = "km/h"
            }

            // 三段動態警示色 (安全/注意/超速警戒)
            val isOverSpeed = data.speedLimit > 0 && currentSpeed > data.speedLimit
            val overDiff = if (isOverSpeed) currentSpeed - data.speedLimit else 0

            when {
                // 1. 嚴重超速 (> 5 km/h)
                overDiff > 5 -> {
                    tvSpeed?.setTextColor(0xFFEF4444.toInt()) // 亮紅
                    tvSub?.setTextColor(0xFFEF4444.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_alert)
                    tvExpandedInfo?.text = "⚠️ 嚴重超速！${data.camType} ${data.distanceMeters}m (限速${data.speedLimit})"
                    tvExpandedInfo?.setTextColor(0xFFEF4444.toInt())
                }
                // 2. 輕微超速 (1 ~ 5 km/h)
                isOverSpeed -> {
                    tvSpeed?.setTextColor(0xFFF59E0B.toInt()) // 琥珀橘
                    tvSub?.setTextColor(0xFFF59E0B.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_warning)
                    tvExpandedInfo?.text = "⚠️ 請注意車速！${data.camType} ${data.distanceMeters}m (限速${data.speedLimit})"
                    tvExpandedInfo?.setTextColor(0xFFF59E0B.toInt())
                }
                // 3. 相機接近中
                data.distanceMeters > 0 -> {
                    tvSpeed?.setTextColor(0xFFFFFFFF.toInt())
                    val subColor = if (data.distanceMeters < 300) 0xFFF59E0B.toInt() else 0xFF00F2FE.toInt()
                    tvSub?.setTextColor(subColor)
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle)
                    tvExpandedInfo?.text = "📷 ${data.camType} ${data.distanceMeters}m (限速${data.speedLimit})"
                    tvExpandedInfo?.setTextColor(0xFF00F2FE.toInt())
                }
                // 4. 路況順暢
                else -> {
                    tvSpeed?.setTextColor(0xFFFFFFFF.toInt())
                    tvSub?.setTextColor(0xFF00F2FE.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle)
                    tvExpandedInfo?.text = if (data.camType.isNotEmpty()) data.camType else "🟢 路況順暢"
                    tvExpandedInfo?.setTextColor(0xFF10B981.toInt())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentService = null
        SpeedTrapListenerService.onDataUpdated = null
        GpsSpeedManager.onGpsSpeedUpdated = null
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
        overlayParams = null
    }
}
