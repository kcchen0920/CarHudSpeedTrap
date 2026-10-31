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
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/**
 * 方案C：車載 Wi-Fi 連線自動觸發接收器
 * 當車機 Wi-Fi 連上手機熱點時，自動背景確認神盾測速並啟動 HUD 懸浮球常駐
 */
class CarWifiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarWifiReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_WIFI_ENABLED = "auto_wifi_enabled"
        private var lastTriggerTime: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
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
        // 60 秒防抖動，避免 Wi-Fi 訊號連線過程多次廣播
        if (now - lastTriggerTime < 60000L) {
            return
        }
        lastTriggerTime = now

        val ssid = getConnectedWifiSsid(context)
        Log.i(TAG, "📶 車機 Wi-Fi 已連線成功 ($ssid)，自動執行連線輔助流程...")

        // 剛開機 45 秒內為開機穩定階段，不進行重複 Activity 喚醒避免畫面閃退與 BAL 異常
        val isRecentBoot = SystemClock.elapsedRealtime() < 45000L

        // 1. 自動檢查神盾測速 (絕不頻繁開啟又關閉畫面)
        if (!isRecentBoot) {
            SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = false)
        }

        // 2. 自動啟動 TopHudOverlayService 懸浮球常駐
        TopHudOverlayService.startIfEnabled(context)
    }

    private fun checkIsWifiConnected(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
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
