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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

        const val PREF_HUD_POS_X = "hud_pos_x"
        const val PREF_HUD_POS_Y = "hud_pos_y"

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

    // 方案二：全螢幕視線餘光呼吸紅光 Overlay (Peripheral Vision Edge Glow)
    private var edgeGlowView: View? = null
    private var edgeGlowParams: WindowManager.LayoutParams? = null
    private var isEdgeGlowVisible: Boolean = false
    private val edgeGlowHandler = Handler(Looper.getMainLooper())
    private var glowPulsePhase: Float = 0.35f
    private var glowPulseIncreasing: Boolean = true
    private val edgeGlowPulseRunnable = object : Runnable {
        override fun run() {
            if (!isEdgeGlowVisible) return
            if (glowPulseIncreasing) {
                glowPulsePhase += 0.08f
                if (glowPulsePhase >= 0.85f) {
                    glowPulsePhase = 0.85f
                    glowPulseIncreasing = false
                }
            } else {
                glowPulsePhase -= 0.08f
                if (glowPulsePhase <= 0.30f) {
                    glowPulsePhase = 0.30f
                    glowPulseIncreasing = true
                }
            }
            edgeGlowView?.alpha = glowPulsePhase
            edgeGlowHandler.postDelayed(this, 60L)
        }
    }

    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        layoutExpandedMenu?.visibility = View.GONE
    }

    // 常駐背景自癒巡檢：每 5 秒確認 GPS 與神盾通知監聽狀態，若受車機冷啟動影響中斷則自動自癒修復
    private val selfHealingHandler = Handler(Looper.getMainLooper())
    private val selfHealingRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // 自癒 1：確保 GPS 監聽常駐活躍 (若尚未啟用或未收到任何定位回呼，自動觸發掛鉤)
            if (!GpsSpeedManager.isGpsActive) {
                GpsSpeedManager.startListening(this@TopHudOverlayService)
            }
            // 自癒 2：若神盾通知服務連線脫鉤，自動透過 requestRebind 觸發安全重連
            if (!SpeedTrapListenerService.isConnected) {
                SpeedTrapListenerService.forceRebind(this@TopHudOverlayService)
            }
            selfHealingHandler.postDelayed(this, 5000L)
        }
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
        // 啟動開機與運行期間背景自癒巡檢
        selfHealingHandler.postDelayed(selfHealingRunnable, 3000L)
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasLocationPerm = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val fgsType = if (hasLocationPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(1001, notification, fgsType)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(1001, notification)
            } catch (ex: Exception) {
                Log.w(TAG, "startForeground fallback 失敗: ${ex.message}")
            }
        }
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
        // 優先讀取使用者前次拖曳自訂儲存的位置，若無則預設右上角
        val savedX = prefs.getInt(PREF_HUD_POS_X, -1)
        val savedY = prefs.getInt(PREF_HUD_POS_Y, -1)
        val defaultX = dm.widthPixels - (84 * dm.density).toInt()
        val defaultY = (24 * dm.density).toInt()
        val initialX = if (savedX >= 0) savedX else defaultX.coerceAtLeast(0)
        val initialY = if (savedY >= 0) savedY else defaultY.coerceAtLeast(0)

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
            x = initialX
            y = initialY
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
                        // 即時動態獲取螢幕解析度，防止開機時解析度尚未穩定導致限制在 0 座標
                        val curDm = resources.displayMetrics
                        val maxX = (curDm.widthPixels - v.width).coerceAtLeast(0)
                        val maxY = (curDm.heightPixels - v.height).coerceAtLeast(0)
                        params.x = (initialParamX + dx).coerceIn(0, maxX)
                        params.y = (initialParamY + dy).coerceIn(0, maxY)
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 拖曳放開後自動保存使用者自訂座標，下次開機自動還原
                        getSharedPreferences("carhud_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt(PREF_HUD_POS_X, params.x)
                            .putInt(PREF_HUD_POS_Y, params.y)
                            .apply()
                    } else {
                        toggleExpandedMenu()
                    }
                    true
                }
                else -> false
            }
        }

        // 點擊詳情訊息可直接開啟診斷主畫面
        overlayView?.findViewById<View>(R.id.tv_expanded_info)?.setOnClickListener {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(mainIntent)
            layoutExpandedMenu?.visibility = View.GONE
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

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 方案二：初始化視線餘光全螢幕呼吸紅光 (不可觸控、零阻擋)
        initEdgeGlowOverlay(layoutType)

        // 載入當前測速快取數據
        updateOverlayUi(SpeedTrapListenerService.latestData)

        // 註冊神盾通知與 GPS 車速回呼 (多觀察者模式，不覆蓋其他組件)
        SpeedTrapListenerService.addDataListener(dataListener)
        GpsSpeedManager.addSpeedListener(speedListener)
    }

    private fun initEdgeGlowOverlay(layoutType: Int) {
        val glowView = View(this).apply {
            setBackgroundResource(R.drawable.bg_edge_glow_red)
            visibility = View.GONE
            alpha = 0f
        }
        val glowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager?.addView(glowView, glowParams)
            edgeGlowView = glowView
            edgeGlowParams = glowParams
        } catch (e: Exception) {
            Log.w(TAG, "初始化 EdgeGlow 失敗: ${e.message}")
        }
    }

    private fun showEdgeGlow(show: Boolean) {
        if (show == isEdgeGlowVisible) return
        isEdgeGlowVisible = show
        if (show) {
            edgeGlowView?.visibility = View.VISIBLE
            edgeGlowHandler.removeCallbacks(edgeGlowPulseRunnable)
            edgeGlowHandler.post(edgeGlowPulseRunnable)
        } else {
            edgeGlowHandler.removeCallbacks(edgeGlowPulseRunnable)
            edgeGlowView?.visibility = View.GONE
        }
    }

    private val dataListener: (SpeedTrapData) -> Unit = { data ->
        updateOverlayUi(data)
    }

    private val speedListener: (Int, String) -> Unit = { speed, _ ->
        val cur = SpeedTrapListenerService.latestData
        updateOverlayUi(cur.copy(currentSpeed = speed))
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

    private val mainUiHandler = Handler(Looper.getMainLooper())

    private fun updateOverlayUi(data: SpeedTrapData) {
        mainUiHandler.post {
            val tvLimit = tvCircleSpeedLimit
            val tvSpeed = tvCircleSpeed
            val tvSub = tvCircleSub
            val badge = circleBadge
            val tvExpandedInfo = overlayView?.findViewById<TextView>(R.id.tv_expanded_info)

            // 雙軌車速智能融合：
            // 1. 若 GPS 晶片傳回即時車速 (> 0)，優先採用高頻 GPS
            // 2. 若神盾通知有車速 (> 0)，在 GPS 尚未就緒前立即補位，絕不卡 0
            // 3. 若 GPS 處於活躍狀態且二者皆為 0，代表車輛真正靜止
            val currentSpeed = when {
                GpsSpeedManager.currentGpsSpeed > 0 -> GpsSpeedManager.currentGpsSpeed
                data.currentSpeed > 0 -> data.currentSpeed
                else -> 0
            }
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

            // 五段動態警示色 (嚴重超速紅 / 輕微超速橘 / 相機近逼黃 / 相機警示金 / 正常巡航青白)
            val isOverSpeed = data.speedLimit > 0 && currentSpeed > data.speedLimit
            val overDiff = if (isOverSpeed) currentSpeed - data.speedLimit else 0
            val isApproachingCam = data.distanceMeters > 0 || (data.speedLimit > 0 && data.camType.isNotEmpty())

            // 方案二：視線餘光全螢幕動態呼吸紅光觸發判定 (超速且距離相機 <= 350m)
            val shouldTriggerEdgeGlow = isOverSpeed && overDiff >= 3 && data.distanceMeters in 1..400
            showEdgeGlow(shouldTriggerEdgeGlow)

            // 方案三：區間測速均速與安全通過建議時速顯示
            if (data.isIntervalSpeedTrap && data.intervalAverageSpeed > 0) {
                if (data.distanceMeters > 0) {
                    tvSub?.text = "均${data.intervalAverageSpeed}|${data.distanceMeters}m"
                } else {
                    tvSub?.text = "均${data.intervalAverageSpeed}"
                }
            }

            // 方案一：神盾後台離線告警提示
            if (!data.isSpeedTrapAlive) {
                tvSub?.text = "神盾離線"
            }

            when {
                // 1. 嚴重超速 (> 5 km/h) -> 鮮紅爆閃警戒
                overDiff > 5 -> {
                    tvSpeed?.setTextColor(0xFFEF4444.toInt()) // 亮紅
                    tvSub?.setTextColor(0xFFEF4444.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_alert)
                    if (data.isIntervalSpeedTrap && data.intervalAverageSpeed > 0) {
                        tvExpandedInfo?.text = "⚠️ 區間超速！均速:${data.intervalAverageSpeed} (建議降至 <= ${data.intervalSafePassSpeed})"
                    } else {
                        tvExpandedInfo?.text = "⚠️ 嚴重超速！${data.camType} ${if (data.distanceMeters > 0) "${data.distanceMeters}m " else ""}(限速${data.speedLimit})"
                    }
                    tvExpandedInfo?.setTextColor(0xFFEF4444.toInt())
                }
                // 2. 輕微超速 (1 ~ 5 km/h) -> 警示橙色
                isOverSpeed -> {
                    tvSpeed?.setTextColor(0xFFF97316.toInt()) // 橙色
                    tvSub?.setTextColor(0xFFF97316.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_warning)
                    if (data.isIntervalSpeedTrap && data.intervalAverageSpeed > 0) {
                        tvExpandedInfo?.text = "⚠️ 請減速！區間均速:${data.intervalAverageSpeed} (建議維持 <= ${data.intervalSafePassSpeed})"
                    } else {
                        tvExpandedInfo?.text = "⚠️ 請減速！${data.camType} ${if (data.distanceMeters > 0) "${data.distanceMeters}m " else ""}(限速${data.speedLimit})"
                    }
                    tvExpandedInfo?.setTextColor(0xFFF97316.toInt())
                }
                // 3. 相機緊急近逼 (距離 <= 300m) -> 鮮黃外框與高亮文字
                isApproachingCam && data.distanceMeters in 1..300 -> {
                    tvSpeed?.setTextColor(0xFFFDE047.toInt()) // 鮮黃
                    tvSub?.setTextColor(0xFFF59E0B.toInt()) // 琥珀橘
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_warning)
                    if (data.isIntervalSpeedTrap && data.intervalAverageSpeed > 0) {
                        tvExpandedInfo?.text = "⏱️ 區間測速中 均速:${data.intervalAverageSpeed} (安全建議 <= ${data.intervalSafePassSpeed})"
                    } else {
                        tvExpandedInfo?.text = "📷 相機近逼！${data.camType} ${data.distanceMeters}m (限速${data.speedLimit})"
                    }
                    tvExpandedInfo?.setTextColor(0xFFF59E0B.toInt())
                }
                // 4. 偵測到前方測速相機 (距離 > 300m) -> 科技金黃外框
                isApproachingCam -> {
                    tvSpeed?.setTextColor(0xFF67E8F9.toInt()) // 亮霓虹青
                    tvSub?.setTextColor(0xFFFBBF24.toInt()) // 金黃距離
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle_cam)
                    if (data.isIntervalSpeedTrap && data.intervalAverageSpeed > 0) {
                        tvExpandedInfo?.text = "⏱️ 區間測速中 均速:${data.intervalAverageSpeed} (安全建議 <= ${data.intervalSafePassSpeed})"
                    } else {
                        tvExpandedInfo?.text = "📷 前方測速：${data.camType} ${if (data.distanceMeters > 0) "${data.distanceMeters}m " else ""}(限速${data.speedLimit})"
                    }
                    tvExpandedInfo?.setTextColor(0xFFFBBF24.toInt())
                }
                // 5. 一般正常巡航 -> 經典夜空黑/青光外框
                else -> {
                    tvSpeed?.setTextColor(0xFFFFFFFF.toInt())
                    tvSub?.setTextColor(0xFF00F2FE.toInt())
                    badge?.setBackgroundResource(R.drawable.bg_hud_circle)
                    if (!data.isSpeedTrapAlive) {
                        tvExpandedInfo?.text = "⚠️ 神盾背景離線，正在自動拉活中..."
                        tvExpandedInfo?.setTextColor(0xFFEF4444.toInt())
                    } else {
                        tvExpandedInfo?.text = if (data.camType.isNotEmpty()) data.camType else "🟢 路況順暢"
                        tvExpandedInfo?.setTextColor(0xFF10B981.toInt())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentService = null
        showEdgeGlow(false)
        selfHealingHandler.removeCallbacks(selfHealingRunnable)
        SpeedTrapListenerService.removeDataListener(dataListener)
        GpsSpeedManager.removeSpeedListener(speedListener)
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        edgeGlowHandler.removeCallbacks(edgeGlowPulseRunnable)
        if (overlayView != null) {
            try {
                if (overlayView?.isAttachedToWindow == true) {
                    windowManager?.removeView(overlayView)
                }
            } catch (e: Exception) {
                Log.w(TAG, "removeView 忽略例外: ${e.message}")
            }
            overlayView = null
        }
        if (edgeGlowView != null) {
            try {
                if (edgeGlowView?.isAttachedToWindow == true) {
                    windowManager?.removeView(edgeGlowView)
                }
            } catch (e: Exception) {
                Log.w(TAG, "removeView edgeGlow 忽略: ${e.message}")
            }
            edgeGlowView = null
        }
        overlayParams = null
        edgeGlowParams = null
    }
}
