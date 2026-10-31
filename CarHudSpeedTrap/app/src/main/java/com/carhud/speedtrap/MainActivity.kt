package com.carhud.speedtrap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isOverlayRunning = false
    private val PREF_NAME = "carhud_prefs"
    private val KEY_CUSTOM_AA_PKG = "custom_aa_pkg"
    private val KEY_AUTO_START_HUD = "auto_start_hud"

    private var simulatedSpeedIndex = 0
    private val testSpeeds = intArrayOf(0, 35, 52, 68, 85, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnNotification = findViewById<Button>(R.id.btn_grant_notification)
        val btnLocation = findViewById<Button>(R.id.btn_grant_location)
        val btnOverlay = findViewById<Button>(R.id.btn_grant_overlay)
        val btnLaunchSpeedTrap = findViewById<Button>(R.id.btn_launch_speedtrap)
        val btnChooseAA = findViewById<Button>(R.id.btn_choose_aa_app)
        val tvCurrentAA = findViewById<TextView>(R.id.tv_current_aa_pkg)
        val btnChooseSpeedTrap = findViewById<Button?>(R.id.btn_choose_speedtrap_app)
        val tvCurrentSpeedTrap = findViewById<TextView?>(R.id.tv_current_speedtrap_pkg)
        val btnTestLaunchSpeedTrap = findViewById<Button?>(R.id.btn_test_launch_speedtrap)
        val btnToggleOverlay = findViewById<Button>(R.id.btn_toggle_overlay)
        val btnTestNormal = findViewById<Button>(R.id.btn_test_normal)
        val btnTestOverspeed = findViewById<Button>(R.id.btn_test_overspeed)
        val btnTestGpsSpeed = findViewById<Button>(R.id.btn_test_gps_speed)
        val btnRebind = findViewById<Button>(R.id.btn_rebind_listener)

        updateCurrentAaLabel(tvCurrentAA)
        updateSpeedTrapLabel(tvCurrentSpeedTrap)

        btnChooseSpeedTrap?.setOnClickListener {
            showSpeedTrapPickerDialog(tvCurrentSpeedTrap)
        }

        btnTestLaunchSpeedTrap?.setOnClickListener {
            SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(this, bringHomeToFront = false, forceLaunch = true)
        }

        // 1. 啟用通知存取權
        btnNotification.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "請在清單中找到「車機神盾HUD」並開啟權限", Toast.LENGTH_LONG).show()
        }

        // 2. 啟用 GPS 定位權限
        btnLocation.setOnClickListener {
            requestLocationPermission()
        }

        // 3. 啟用懸浮窗權限
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "懸浮窗權限已啟用！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 4. 強制重新連線 NotificationListener
        btnRebind.setOnClickListener {
            SpeedTrapListenerService.forceRebind(this)
            Toast.makeText(this, "已發送強制重連指令！請確認狀態是否變綠燈", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ updateDiagnosticPanel() }, 1000)
        }

        // 5. 立即啟動神盾 App
        btnLaunchSpeedTrap.setOnClickListener {
            if (SpeedTrapListenerService.checkSpeedTrapInstalled(this)) {
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(this, bringHomeToFront = false, forceLaunch = true)
                Toast.makeText(this, "正在開啟神盾測速照相...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "車機尚未安裝神盾測速照相，正跳轉至下載頁面...", Toast.LENGTH_LONG).show()
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SpeedTrapListenerService.SPEEDTRAP_PACKAGE}"))
                    startActivity(marketIntent)
                } catch (e: Exception) {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${SpeedTrapListenerService.SPEEDTRAP_PACKAGE}"))
                    startActivity(webIntent)
                }
            }
        }

        // 6. 選擇自訂 Android Auto 投屏 App
        btnChooseAA.setOnClickListener {
            showAppPickerDialog(tvCurrentAA)
        }

        // 7. 開關全域置頂橫條
        btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先取得懸浮窗權限！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val overlayIntent = Intent(this, TopHudOverlayService::class.java)
            if (!isOverlayRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, overlayIntent)
                } else {
                    startService(overlayIntent)
                }
                btnToggleOverlay.text = "關閉全域置頂 HUD 懸浮球 (右上角)"
                isOverlayRunning = true
            } else {
                stopService(overlayIntent)
                btnToggleOverlay.text = "開啟全域置頂 HUD 懸浮球 (右上角)"
                isOverlayRunning = false
            }
        }

        // 8. 模擬測試數據 (支援即時連動 Widget 與 HUD 懸浮條)
        btnTestNormal.setOnClickListener {
            val testData = SpeedTrapData(
                speedLimit = 50,
                distanceMeters = 350,
                currentSpeed = 48,
                camType = "固定測速",
                rawText = "前方 350 公尺 固定測速照相 限速 50"
            )
            SpeedTrapListenerService.latestData = testData
            CarHudWidgetProvider.updateWidgetData(this, 50, 350, 48, "固定測速")
            SpeedTrapListenerService.onDataUpdated?.invoke(testData)
            Toast.makeText(this, "已推送正常數據：速限 50 / 車速 48 / 距 350m", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        btnTestOverspeed.setOnClickListener {
            val testData = SpeedTrapData(
                speedLimit = 50,
                distanceMeters = 180,
                currentSpeed = 68,
                camType = "⚠️ 超速！固定測速",
                rawText = "前方 180 公尺 固定測速照相 限速 50 超速"
            )
            SpeedTrapListenerService.latestData = testData
            CarHudWidgetProvider.updateWidgetData(this, 50, 180, 68, "⚠️ 超速！固定測速")
            SpeedTrapListenerService.onDataUpdated?.invoke(testData)
            Toast.makeText(this, "已推送超速數據：速限 50 / 車速 68 / 距 180m", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        btnTestGpsSpeed.setOnClickListener {
            simulatedSpeedIndex = (simulatedSpeedIndex + 1) % testSpeeds.size
            val simSpeed = testSpeeds[simulatedSpeedIndex]
            GpsSpeedManager.simulateSpeed(simSpeed)
            Toast.makeText(this, "已模擬 GPS 時速跳動為: $simSpeed km/h", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        // 9. HUD 透明度切換 (防遮蔽前台地圖/導航 App)
        val tvHudAlphaLabel = findViewById<TextView>(R.id.tv_hud_alpha_label)
        val btnAlphaExtreme = findViewById<Button>(R.id.btn_alpha_extreme)
        val btnAlphaHigh = findViewById<Button>(R.id.btn_alpha_high)
        val btnAlphaStandard = findViewById<Button>(R.id.btn_alpha_standard)
        val btnExitApp = findViewById<Button>(R.id.btn_exit_app)
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 9.5 HUD 尺寸縮放 (放大 / 縮小) 控制
        val tvHudScaleLabel = findViewById<TextView>(R.id.tv_hud_scale_label)
        val btnScaleCompact = findViewById<Button>(R.id.btn_scale_compact)
        val btnScaleNormal = findViewById<Button>(R.id.btn_scale_normal)
        val btnScaleLarge = findViewById<Button>(R.id.btn_scale_large)
        val btnScaleXLarge = findViewById<Button>(R.id.btn_scale_xlarge)

        fun updateScaleUi(scale: Float) {
            val percent = (scale * 100).toInt()
            tvHudScaleLabel.text = "HUD 懸浮球尺寸大小（放大 / 縮小）：目前為 $percent%"
            btnScaleCompact.backgroundTintList = ColorStateList.valueOf(if (scale <= 0.85f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnScaleNormal.backgroundTintList = ColorStateList.valueOf(if (scale in 0.86f..1.10f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnScaleLarge.backgroundTintList = ColorStateList.valueOf(if (scale in 1.11f..1.35f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnScaleXLarge.backgroundTintList = ColorStateList.valueOf(if (scale > 1.35f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
        }

        val curScale = prefs.getFloat(TopHudOverlayService.PREF_HUD_SCALE, TopHudOverlayService.DEFAULT_HUD_SCALE)
        updateScaleUi(curScale)

        btnScaleCompact.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_SCALE, 0.75f).apply()
            TopHudOverlayService.updateScale(0.75f)
            updateScaleUi(0.75f)
            Toast.makeText(this, "已切換為迷你尺寸 (75%)", Toast.LENGTH_SHORT).show()
        }
        btnScaleNormal.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_SCALE, 1.0f).apply()
            TopHudOverlayService.updateScale(1.0f)
            updateScaleUi(1.0f)
            Toast.makeText(this, "已切換為標準尺寸 (100%)", Toast.LENGTH_SHORT).show()
        }
        btnScaleLarge.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_SCALE, 1.25f).apply()
            TopHudOverlayService.updateScale(1.25f)
            updateScaleUi(1.25f)
            Toast.makeText(this, "已切換為放大尺寸 (125%)", Toast.LENGTH_SHORT).show()
        }
        btnScaleXLarge.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_SCALE, 1.50f).apply()
            TopHudOverlayService.updateScale(1.50f)
            updateScaleUi(1.50f)
            Toast.makeText(this, "已切換為特大尺寸 (150%)", Toast.LENGTH_SHORT).show()
        }

        // 開啟 App 時自動確保 HUD 懸浮球常駐
        TopHudOverlayService.startIfEnabled(this)

        fun updateAlphaUi(alpha: Float) {
            val percent = (alpha * 100).toInt()
            tvHudAlphaLabel.text = "HUD 懸浮球透明度（防遮擋前台 App）：目前為 ${(100 - percent)}% 透明 (不透明度 $percent%)"
            btnAlphaExtreme.backgroundTintList = ColorStateList.valueOf(if (alpha <= 0.55f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnAlphaHigh.backgroundTintList = ColorStateList.valueOf(if (alpha in 0.56f..0.78f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnAlphaStandard.backgroundTintList = ColorStateList.valueOf(if (alpha > 0.78f) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
        }

        val curAlpha = prefs.getFloat(TopHudOverlayService.PREF_HUD_ALPHA, TopHudOverlayService.DEFAULT_HUD_ALPHA)
        updateAlphaUi(curAlpha)

        btnAlphaExtreme.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_ALPHA, 0.50f).apply()
            TopHudOverlayService.updateAlpha(0.50f)
            updateAlphaUi(0.50f)
            Toast.makeText(this, "已切換為極度通透模式 (50%)", Toast.LENGTH_SHORT).show()
        }

        btnAlphaHigh.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_ALPHA, 0.72f).apply()
            TopHudOverlayService.updateAlpha(0.72f)
            updateAlphaUi(0.72f)
            Toast.makeText(this, "已切換為高透模式 (72%)", Toast.LENGTH_SHORT).show()
        }

        btnAlphaStandard.setOnClickListener {
            prefs.edit().putFloat(TopHudOverlayService.PREF_HUD_ALPHA, 0.88f).apply()
            TopHudOverlayService.updateAlpha(0.88f)
            updateAlphaUi(0.88f)
            Toast.makeText(this, "已切換為標準透光模式 (88%)", Toast.LENGTH_SHORT).show()
        }

        // 🚗 方案 6.1: 客廳電視返家到站連動 UI 控制邏輯
        val cbHomeEtaEnabled = findViewById<CheckBox>(R.id.cb_home_eta_enabled)
        val tvHomeCoords = findViewById<TextView>(R.id.tv_home_coords)
        val btnSetHomeCoords = findViewById<Button>(R.id.btn_set_home_coords)
        val etHomeChannel = findViewById<EditText>(R.id.et_home_channel)
        val btnTestHomeEta = findViewById<Button>(R.id.btn_test_home_eta)

        fun updateHomeCoordsUi() {
            val coords = CarHomeEtaManager.getHomeLocation(this)
            if (coords != null) {
                tvHomeCoords.text = String.format("目前住家位置：緯度 %.4f, 經度 %.4f", coords.first, coords.second)
                tvHomeCoords.setTextColor(0xFF10B981.toInt())
            } else {
                tvHomeCoords.text = "目前住家位置：尚未設定 (點擊下方按鈕以目前 GPS 設定)"
                tvHomeCoords.setTextColor(0xFFF59E0B.toInt())
            }
        }

        cbHomeEtaEnabled.isChecked = CarHomeEtaManager.isEnabled(this)
        cbHomeEtaEnabled.setOnCheckedChangeListener { _, isChecked ->
            CarHomeEtaManager.setEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "已啟用返家電視推播連動" else "已關閉返家電視推播", Toast.LENGTH_SHORT).show()
        }

        updateHomeCoordsUi()
        etHomeChannel.setText(CarHomeEtaManager.getChannel(this))
        etHomeChannel.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                CarHomeEtaManager.setChannel(this@MainActivity, s?.toString() ?: CarHomeEtaManager.DEFAULT_CHANNEL)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnSetHomeCoords.setOnClickListener {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var bestLoc: Location? = null
            try {
                val gpsLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                bestLoc = when {
                    gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                    gpsLoc != null -> gpsLoc
                    else -> netLoc
                }
            } catch (e: SecurityException) {
                Toast.makeText(this, "請先授予 GPS 定位權限", Toast.LENGTH_SHORT).show()
            }

            if (bestLoc != null) {
                CarHomeEtaManager.setHomeLocation(this, bestLoc.latitude, bestLoc.longitude)
                updateHomeCoordsUi()
                Toast.makeText(this, String.format("已設定住家: %.4f, %.4f", bestLoc.latitude, bestLoc.longitude), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "目前尚未取得有效 GPS 定位，請至開闊處稍候重試", Toast.LENGTH_LONG).show()
            }
        }

        btnTestHomeEta.setOnClickListener {
            Toast.makeText(this, "正在發送返家模擬推播至客廳電視...", Toast.LENGTH_SHORT).show()
            CarHomeEtaManager.sendTestPush(this) { success, msg ->
                Toast.makeText(this, if (success) "✅ 客廳電視已收到推播！($msg)" else "❌ 推播失敗: $msg", Toast.LENGTH_LONG).show()
            }
        }

        // 10. 徹底關閉並退出本程式
        btnExitApp.setOnClickListener {
            stopService(Intent(this, TopHudOverlayService::class.java))
            GpsSpeedManager.stopListening()
            Toast.makeText(this, "正在徹底退出 CarHud 測速整合...", Toast.LENGTH_SHORT).show()
            finishAffinity()
        }

        // 註冊即時診斷與 GPS 回呼
        SpeedTrapListenerService.onDebugInfoUpdated = {
            runOnUiThread { updateDiagnosticPanel() }
        }

        GpsSpeedManager.onGpsSpeedUpdated = { _, _ ->
            runOnUiThread { updateDiagnosticPanel() }
        }
    }

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ), 1001
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✓ GPS 定位權限授權成功！正在搜尋衛星...", Toast.LENGTH_SHORT).show()
                GpsSpeedManager.startListening(this)
            } else {
                Toast.makeText(this, "未授予 GPS 權限，無法即時測速", Toast.LENGTH_SHORT).show()
            }
            updateDiagnosticPanel()
        }
    }

    private fun updateDiagnosticPanel() {
        val tvStatus = findViewById<TextView>(R.id.tv_listener_status)
        val tvSpeedTrapStatus = findViewById<TextView>(R.id.tv_speedtrap_status)
        val tvTime = findViewById<TextView>(R.id.tv_diag_time)
        val tvPkg = findViewById<TextView>(R.id.tv_diag_pkg)
        val tvRaw = findViewById<TextView>(R.id.tv_diag_raw)
        val tvParsed = findViewById<TextView>(R.id.tv_diag_parsed)
        val tvGps = findViewById<TextView>(R.id.tv_diag_gps)
        val tvGpsDetail = findViewById<TextView>(R.id.tv_diag_gps_detail)

        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

        if (!isGranted) {
            tvStatus.text = "● 監聽服務狀態: ❌ 尚未取得系統通知權限 (請按上方按鈕1)"
            tvStatus.setTextColor(0xFFEF4444.toInt())
        } else if (SpeedTrapListenerService.isConnected) {
            tvStatus.text = "● 監聽服務狀態: 🟢 已連線運作中 (Listening)"
            tvStatus.setTextColor(0xFF10B981.toInt())
        } else {
            tvStatus.text = "● 監聽服務狀態: 🟡 權限已開但服務未連線 (請點強制重連)"
            tvStatus.setTextColor(0xFFF59E0B.toInt())
        }

        // 神盾 App 安裝偵測
        val isSpeedTrapInstalled = SpeedTrapListenerService.checkSpeedTrapInstalled(this)
        if (isSpeedTrapInstalled) {
            val lastNotif = SpeedTrapListenerService.lastNotificationTimeMillis
            val isRecentlyActive = lastNotif > 0 && (System.currentTimeMillis() - lastNotif) < 30000L
            if (isRecentlyActive) {
                tvSpeedTrapStatus.text = "● 神盾 App: 🟢 正在背景常駐運行中"
                tvSpeedTrapStatus.setTextColor(0xFF10B981.toInt())
            } else {
                tvSpeedTrapStatus.text = "● 神盾 App: 🟡 已安裝 (尚未收到背景通知，可按右側啟動)"
                tvSpeedTrapStatus.setTextColor(0xFFF59E0B.toInt())
            }
        } else {
            tvSpeedTrapStatus.text = "● 神盾 App: ❌ 尚未安裝 (本工具需搭配神盾使用)"
            tvSpeedTrapStatus.setTextColor(0xFFEF4444.toInt())
        }

        tvTime.text = "最後收到通知時間: ${SpeedTrapListenerService.lastNotificationTime}"
        tvPkg.text = "通知 App 來源: ${SpeedTrapListenerService.lastPackageName}"
        tvRaw.text = "原始通知文字: ${SpeedTrapListenerService.lastRawText}"

        val cur = SpeedTrapListenerService.latestData
        val displaySpeed = if (GpsSpeedManager.currentGpsSpeed > 0) GpsSpeedManager.currentGpsSpeed else cur.currentSpeed
        val displayLimit = if (cur.speedLimit > 0) "${cur.speedLimit}" else "--"
        tvParsed.text = "解析結果: 速限 $displayLimit | 距離 ${cur.distanceMeters}m | 車速 $displaySpeed | ${cur.camType}"

        // GPS 車速與衛星狀態
        if (GpsSpeedManager.isGpsActive) {
            tvGps.text = "車載 GPS 狀態: 🟢 定位連線中 (即時車速: ${GpsSpeedManager.currentGpsSpeed} km/h)"
            tvGps.setTextColor(0xFF38BDF8.toInt())
        } else {
            tvGps.text = "車載 GPS 狀態: 🟡 搜尋衛星中 (來源: ${GpsSpeedManager.lastSpeedSource})"
            tvGps.setTextColor(0xFFF59E0B.toInt())
        }
        tvGpsDetail.text = "定位資訊: ${GpsSpeedManager.lastLocationDesc}"
    }

    private fun updateCurrentAaLabel(tv: TextView) {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val customPkg = prefs.getString(KEY_CUSTOM_AA_PKG, null)
        if (!customPkg.isNullOrEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(customPkg, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                tv.text = "目前綁定：$appName ($customPkg)"
                return
            } catch (e: Exception) {}
        }
        tv.text = "目前綁定：自動智慧偵測 (ZLINK / AutoKit / 投屏)"
    }

    private fun showAppPickerDialog(tv: TextView) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)

        val appNames = mutableListOf<String>()
        val packageNames = mutableListOf<String>()

        for (info in resolveInfos) {
            val name = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            if (pkg != packageName) {
                appNames.add("$name ($pkg)")
                packageNames.add(pkg)
            }
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("請選擇車機上的 Android Auto / 投屏應用")
        builder.setItems(appNames.toTypedArray()) { _, which ->
            val selectedPkg = packageNames[which]
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_CUSTOM_AA_PKG, selectedPkg).apply()
            updateCurrentAaLabel(tv)
            Toast.makeText(this, "已成功綁定！點擊懸浮球選單中 AA 按鈕將直接開啟此應用", Toast.LENGTH_LONG).show()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        isOverlayRunning = TopHudOverlayService.isRunning
        val btnToggleOverlay = findViewById<Button?>(R.id.btn_toggle_overlay)
        btnToggleOverlay?.text = if (TopHudOverlayService.isRunning) "關閉全域置頂 HUD 懸浮球 (右上角)" else "開啟全域置頂 HUD 懸浮球 (右上角)"
        checkPermissions()
        TopHudOverlayService.startIfEnabled(this)
        updateDiagnosticPanel()
        updateSpeedTrapLabel(findViewById(R.id.tv_current_speedtrap_pkg))

        // 啟動 GPS 監聽
        GpsSpeedManager.startListening(this)

        // 若已獲取通知存取權但尚未連線，自動嘗試 forceRebind
        val hasNotification = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (hasNotification && !SpeedTrapListenerService.isConnected) {
            SpeedTrapListenerService.forceRebind(this)
        }
    }

    private fun updateSpeedTrapLabel(tv: TextView?) {
        if (tv == null) return
        val detectedPkg = SpeedTrapAutoLauncher.findSpeedTrapPackage(this)
        if (detectedPkg != null) {
            try {
                val appInfo = packageManager.getApplicationInfo(detectedPkg, 0)
                val label = packageManager.getApplicationLabel(appInfo).toString()
                tv.text = "神盾應用：🟢 已識別到 $label ($detectedPkg)"
                tv.setTextColor(0xFF10B981.toInt())
                return
            } catch (e: Exception) {}
        }
        tv.text = "神盾應用：🔴 尚未識別到，請點擊下方「手動選擇神盾 App」"
        tv.setTextColor(0xFFEF4444.toInt())
    }

    private fun showSpeedTrapPickerDialog(tv: TextView?) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)

        val appNames = mutableListOf<String>()
        val packageNames = mutableListOf<String>()

        for (info in resolveInfos) {
            val name = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            if (pkg != packageName) {
                appNames.add("$name ($pkg)")
                packageNames.add(pkg)
            }
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("請選擇車機上的神盾測速 App")
        builder.setItems(appNames.toTypedArray()) { _, which ->
            val selectedPkg = packageNames[which]
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(SpeedTrapAutoLauncher.KEY_CUSTOM_SPEEDTRAP_PKG, selectedPkg).apply()
            updateSpeedTrapLabel(tv)
            Toast.makeText(this, "已綁定神盾應用！正在測試開啟...", Toast.LENGTH_SHORT).show()
            SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(this, bringHomeToFront = false, forceLaunch = true)
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun checkPermissions() {
        val btnNotification = findViewById<Button>(R.id.btn_grant_notification)
        val hasNotification = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (hasNotification) {
            btnNotification.text = "✓ 1. 通知存取權已啟用 (監聽中)"
            btnNotification.setTextColor(0xFF10B981.toInt())
            btnNotification.isEnabled = false
        }

        val btnLocation = findViewById<Button>(R.id.btn_grant_location)
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            btnLocation.text = "✓ 2. GPS 定位權限已啟用"
            btnLocation.setTextColor(0xFF10B981.toInt())
            btnLocation.isEnabled = false
        } else {
            requestLocationPermission()
        }

        val btnOverlay = findViewById<Button>(R.id.btn_grant_overlay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            btnOverlay.text = "✓ 3. 懸浮窗權限已啟用"
            btnOverlay.setTextColor(0xFF10B981.toInt())
            btnOverlay.isEnabled = false
        }
    }

}
