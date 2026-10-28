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
        val btnTestNormal = findViewById<Button>(R.id.btn_test_normal)
        val btnTestOverspeed = findViewById<Button>(R.id.btn_test_overspeed)
        val btnRebind = findViewById<Button>(R.id.btn_rebind_listener)

        // 讀取目前綁定的 AA App
        updateCurrentAaLabel(tvCurrentAA)

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

        // 3. 強制重新連線 NotificationListener
        btnRebind.setOnClickListener {
            SpeedTrapListenerService.forceRebind(this)
            Toast.makeText(this, "已發送強制重連指令！請確認狀態是否變綠燈", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        // 4. 選擇自訂 Android Auto 投屏 App
        btnChooseAA.setOnClickListener {
            showAppPickerDialog(tvCurrentAA)
        }

        // 5. 開關全域置頂橫條
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

        // 6. 模擬測試數據
        btnTestNormal.setOnClickListener {
            CarHudWidgetProvider.updateWidgetData(this, 60, 350, 54, "固定測速")
            Toast.makeText(this, "已推送正常數據：速限 60 / 車速 54 / 距 350m", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        btnTestOverspeed.setOnClickListener {
            CarHudWidgetProvider.updateWidgetData(this, 60, 180, 72, "⚠️ 超速！固定測速")
            Toast.makeText(this, "已推送超速數據：速限 60 / 車速 72 / 距 180m", Toast.LENGTH_SHORT).show()
            updateDiagnosticPanel()
        }

        // 註冊即時診斷回呼
        SpeedTrapListenerService.onDebugInfoUpdated = {
            runOnUiThread {
                updateDiagnosticPanel()
            }
        }
    }

    private fun updateDiagnosticPanel() {
        val tvStatus = findViewById<TextView>(R.id.tv_listener_status)
        val tvTime = findViewById<TextView>(R.id.tv_diag_time)
        val tvPkg = findViewById<TextView>(R.id.tv_diag_pkg)
        val tvRaw = findViewById<TextView>(R.id.tv_diag_raw)
        val tvParsed = findViewById<TextView>(R.id.tv_diag_parsed)

        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

        if (!isGranted) {
            tvStatus.text = "● 監聽服務狀態: ❌ 尚未取得系統通知權限"
            tvStatus.setTextColor(0xFFEF4444.toInt())
        } else if (SpeedTrapListenerService.isConnected) {
            tvStatus.text = "● 監聽服務狀態: 🟢 已連線運作中 (Listening)"
            tvStatus.setTextColor(0xFF10B981.toInt())
        } else {
            tvStatus.text = "● 監聽服務狀態: 🟡 權限已開但服務未連線 (請點右側強制重連)"
            tvStatus.setTextColor(0xFFF59E0B.toInt())
        }

        tvTime.text = "最後收到通知時間: ${SpeedTrapListenerService.lastNotificationTime}"
        tvPkg.text = "通知 App 來源: ${SpeedTrapListenerService.lastPackageName}"
        tvRaw.text = "原始通知文字: ${SpeedTrapListenerService.lastRawText}"

        val cur = SpeedTrapListenerService.latestData
        tvParsed.text = "解析結果: 速限 ${cur.speedLimit} | 距離 ${cur.distanceMeters}m | 車速 ${cur.currentSpeed} | ${cur.camType}"
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
        updateDiagnosticPanel()
    }

    private fun checkPermissions() {
        val hasNotification = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val btnNotification = findViewById<Button>(R.id.btn_grant_notification)
        if (hasNotification) {
            btnNotification.text = "✓ 通知存取權已啟用 (監聽中)"
            btnNotification.isEnabled = false
        }
    }
}
