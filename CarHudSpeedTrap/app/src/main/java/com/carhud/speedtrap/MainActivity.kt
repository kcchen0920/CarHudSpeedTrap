package com.carhud.speedtrap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private var isOverlayRunning = false
    private val PREF_NAME = "carhud_prefs"
    private val KEY_CUSTOM_AA_PKG = "custom_aa_pkg"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnNotification = findViewById<Button>(R.id.btn_grant_notification)
        val btnOverlay = findViewById<Button>(R.id.btn_grant_overlay)
        val btnChooseAA = findViewById<Button>(R.id.btn_choose_aa_app)
        val tvCurrentAA = findViewById<TextView>(R.id.tv_current_aa_pkg)
        val btnToggleOverlay = findViewById<Button>(R.id.btn_toggle_overlay)
        val btnToggleBtAuto = findViewById<Button>(R.id.btn_toggle_bt_auto)
        val btnTestClear = findViewById<Button>(R.id.btn_test_clear)
        val btnTestNormal = findViewById<Button>(R.id.btn_test_normal)
        val btnTestWarn = findViewById<Button>(R.id.btn_test_warn)
        val btnTestOverspeed = findViewById<Button>(R.id.btn_test_overspeed)

        // 讀取目前綁定的 AA App
        updateCurrentAaLabel(tvCurrentAA)

        // 讀取藍牙連動狀態
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var isBtAuto = prefs.getBoolean(CarBluetoothReceiver.KEY_AUTO_BT_ENABLED, true)
        updateBtAutoButtonText(btnToggleBtAuto, isBtAuto)

        btnToggleBtAuto.setOnClickListener {
            isBtAuto = !isBtAuto
            prefs.edit().putBoolean(CarBluetoothReceiver.KEY_AUTO_BT_ENABLED, isBtAuto).apply()
            updateBtAutoButtonText(btnToggleBtAuto, isBtAuto)
            Toast.makeText(this, if (isBtAuto) "已啟用上車藍牙自動化！" else "已停用藍牙自動化", Toast.LENGTH_SHORT).show()
        }

        // 1. 跳轉開啟通知監聽權限
        btnNotification.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "請在清單中找到「車機神盾HUD」並開啟權限", Toast.LENGTH_LONG).show()
        }

        // 2. 跳轉開啟懸浮窗權限
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

        // 3. 選擇自訂 Android Auto 投屏 App
        btnChooseAA.setOnClickListener {
            showAppPickerDialog(tvCurrentAA)
        }

        // 4. 開關全域置頂橫條
        btnToggleOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先取得懸浮窗權限！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val overlayIntent = Intent(this, TopHudOverlayService::class.java)
            if (!isOverlayRunning) {
                startService(overlayIntent)
                btnToggleOverlay.text = "關閉全域置頂懸浮橫條"
                isOverlayRunning = true
            } else {
                stopService(overlayIntent)
                btnToggleOverlay.text = "開啟全域置頂懸浮橫條 (選用)"
                isOverlayRunning = false
            }
        }

        // 5. 模擬測試 (四種路況與警示階段)
        btnTestClear.setOnClickListener {
            val data = SpeedTrapData(60, 0, 50, "路況順暢")
            CarHudWidgetProvider.updateWidgetData(this, 60, 0, 50, "🟢 路況順暢")
            SpeedTrapListenerService.onDataUpdated?.invoke(data)
            Toast.makeText(this, "已推送：🟢 路況順暢 (倒數清空)", Toast.LENGTH_SHORT).show()
        }

        btnTestNormal.setOnClickListener {
            val data = SpeedTrapData(60, 450, 52, "固定測速")
            CarHudWidgetProvider.updateWidgetData(this, 60, 450, 52, "固定測速")
            SpeedTrapListenerService.onDataUpdated?.invoke(data)
            Toast.makeText(this, "已推送巡航：速限 60 / 車速 52 / 距 450m", Toast.LENGTH_SHORT).show()
        }

        btnTestWarn.setOnClickListener {
            val data = SpeedTrapData(60, 180, 62, "固定測速")
            CarHudWidgetProvider.updateWidgetData(this, 60, 180, 62, "固定測速")
            SpeedTrapListenerService.onDataUpdated?.invoke(data)
            Toast.makeText(this, "已推送⚠️近距/微超警告：速限 60 / 車速 62 (琥珀光)", Toast.LENGTH_SHORT).show()
        }

        btnTestOverspeed.setOnClickListener {
            val data = SpeedTrapData(60, 220, 78, "固定測速")
            CarHudWidgetProvider.updateWidgetData(this, 60, 220, 78, "固定測速")
            SpeedTrapListenerService.onDataUpdated?.invoke(data)
            Toast.makeText(this, "已推送🚨嚴重超速警戒：速限 60 / 車速 78 (紅光警示)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBtAutoButtonText(btn: Button, isEnabled: Boolean) {
        if (isEnabled) {
            btn.text = "🚗 藍牙自動化：已啟用 (上車自動啟動)"
            btn.setTextColor(0xFF10B981.toInt())
        } else {
            btn.text = "🚗 藍牙自動化：已停用 (點擊啟用)"
            btn.setTextColor(0xFF94A3B8.toInt())
        }
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
        tv.text = "目前綁定：自動智慧識別 (ZLINK / AutoKit / 投屏)"
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
            Toast.makeText(this, "已成功綁定！點擊橫條 AA 按鈕將直接開啟此應用", Toast.LENGTH_LONG).show()
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        val hasNotification = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val btnNotification = findViewById<Button>(R.id.btn_grant_notification)
        if (hasNotification) {
            btnNotification.text = "✓ 通知存取權已啟用 (正常監聽中)"
            btnNotification.isEnabled = false
        }
    }
}
