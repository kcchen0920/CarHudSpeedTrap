package com.carhud.speedtrap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 方案C：車載 Wi-Fi 連線自動觸發接收器
 * 當車機 Wi-Fi 連上手機熱點時，自動背景喚醒神盾測速並啟動 HUD 懸浮橫條
 */
class CarWifiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarWifiReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_WIFI_ENABLED = "auto_wifi_enabled"
        private var lastTriggerTime: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_AUTO_WIFI_ENABLED, true)

        if (!isEnabled) {
            Log.d(TAG, "Wi-Fi 連線自動化已停用，略過事件")
            return
        }

        val isConnected = checkIsWifiConnected(context, intent)
        if (!isConnected) {
            return
        }

        val now = System.currentTimeMillis()
        // 15 秒防抖動，避免 Wi-Fi 訊號連線過程多次廣播
        if (now - lastTriggerTime < 15000L) {
            return
        }
        lastTriggerTime = now

        val ssid = getConnectedWifiSsid(context)
        Log.i(TAG, "📶 車機 Wi-Fi 已連線成功 ($ssid)，自動執行方案 C 喚醒流程...")
        Toast.makeText(context, "📶 Wi-Fi 已連線 ($ssid)，自動啟動神盾 HUD！", Toast.LENGTH_SHORT).show()

        // 1. 自動在背景喚醒神盾測速 (將畫面退回桌面/背景)
        SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)

        // 2. 自動啟動 TopHudOverlayService 懸浮球常駐
        TopHudOverlayService.startIfEnabled(context)
    }

    private fun checkIsWifiConnected(context: Context, intent: Intent): Boolean {
        // 方案 1: 從 WifiManager.NETWORK_STATE_CHANGED_ACTION Extra 檢查
        if (WifiManager.NETWORK_STATE_CHANGED_ACTION == intent.action) {
            val netInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
            }
            if (netInfo != null && netInfo.isConnected) {
                return true
            }
        }

        // 方案 2: 主動透過 ConnectivityManager 驗證當前 Wi-Fi 狀態
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private fun getConnectedWifiSsid(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info: WifiInfo? = wifiManager.connectionInfo
            val rawSsid = info?.ssid ?: ""
            if (rawSsid.isNotEmpty() && rawSsid != "<unknown ssid>") {
                rawSsid.replace("\"", "")
            } else {
                "手機熱點"
            }
        } catch (e: Exception) {
            "手機熱點"
        }
    }
}
