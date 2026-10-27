package com.carhud.speedtrap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object QuickSwitchHelper {

    private const val TAG = "QuickSwitchHelper"
    private const val PREF_NAME = "carhud_prefs"
    private const val KEY_CUSTOM_AA_PKG = "custom_aa_pkg"

    // 涵蓋市面上 99% 外接盒車機的 Android Auto / 投屏接收端套件名稱清單
    private val KNOWN_AA_PACKAGES = listOf(
        "com.zjinnova.zlink",              // ZLINK / ZLINK5
        "com.zjinnova.zlink.carlink",
        "cn.manstep.phonemirrorbox",       // AutoKit (Carlinkit 專用)
        "cn.manstep.phonemirror",
        "com.carlink.main",                // CarLink
        "com.suding.speedplay",            // SpeedPlay
        "com.turing.carplay",              // TLink
        "com.carletter.main",              // CarLetter
        "com.carletter",
        "com.xyauto.carlink",              // 欣展車機
        "com.ts.carplay",
        "com.hsae.carplay",
        "com.google.android.projection.gearhead", // 原生 Android Auto
        "com.carplay.client",
        "com.nforetek.bt.carplay"
    )

    fun launchAndroidAuto(context: Context) {
        val pm = context.packageManager

        // 1. 優先使用手動指定的自訂 App (若有)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val customPkg = prefs.getString(KEY_CUSTOM_AA_PKG, null)
        if (!customPkg.isNullOrEmpty()) {
            val intent = pm.getLaunchIntentForPackage(customPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "正在啟動 Android Auto...", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 2. 匹配已知車機投屏套件名
        for (pkg in KNOWN_AA_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "正在啟動 Android Auto ($pkg)...", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 3. 智慧關鍵字模糊搜尋：找出車機上名稱包含 Auto / Link / Play 的投屏 App
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pkg = app.packageName.lowercase()
            if (label.contains("zlink") || label.contains("autokit") || label.contains("carlink") ||
                label.contains("speedplay") || label.contains("carplay") || label.contains("android auto") ||
                pkg.contains("zlink") || pkg.contains("autokit") || pkg.contains("phonemirror")) {
                
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    // 記憶該 App
                    prefs.edit().putString(KEY_CUSTOM_AA_PKG, app.packageName).apply()
                    Toast.makeText(context, "已自動識別並啟動: $label", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        // 4. 若真的找不到，才提示並打開設定
        Toast.makeText(context, "未找到車載投屏 App，請在控制台選擇您的 AA 應用", Toast.LENGTH_LONG).show()
        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }

    /**
     * 強制直接開關 Wi-Fi，不跳進設定介面
     */
    fun toggleWifi(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            @Suppress("DEPRECATION")
            val isEnabled = wifiManager.isWifiEnabled
            val targetState = !isEnabled

            // 1. 車機韌體直接調用 setWifiEnabled
            @Suppress("DEPRECATION")
            val success = wifiManager.setWifiEnabled(targetState)

            if (success) {
                val statusText = if (targetState) "已直接開啟 Wi-Fi (連線熱點)" else "已直接關閉 Wi-Fi"
                Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "直接切換 Wi-Fi 異常，嘗試備用方案: ${e.message}")
        }

        // 備用方案 (若 Android 10+ 限制嚴格，使用浮動面板)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(panelIntent)
            } catch (e: Exception) {
                val wifiSettings = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(wifiSettings)
            }
        }
    }
}
