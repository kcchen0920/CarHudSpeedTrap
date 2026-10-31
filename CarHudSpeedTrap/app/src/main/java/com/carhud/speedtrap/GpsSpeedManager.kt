package com.carhud.speedtrap

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 車載 GPS 高頻即時測速引擎 (每 0.5s 刷新)
 * 解決車機晶片未提供 location.hasSpeed() 的 0 時速問題，
 * 支援座標位移微分演算法 (距離/時間推算)、多重 Provider 容錯 (GPS + Network) 及診斷資訊回報。
 */
object GpsSpeedManager {

    private const val TAG = "GpsSpeedManager"

    @Volatile
    var currentGpsSpeed: Int = 0

    @Volatile
    var isGpsActive: Boolean = false

    @Volatile
    var lastLocationDesc: String = "尚未取得定位訊號"

    @Volatile
    var lastSpeedSource: String = "無"

    private var appContext: Context? = null
    private var locationManager: LocationManager? = null
    private var isListening = false

    private var lastLocation: Location? = null
    private var lastLocationTimeMs: Long = 0L

    // 供 UI / MainActivity / HUD 監聽 GPS 狀態更新
    var onGpsSpeedUpdated: ((speed: Int, desc: String) -> Unit)? = null

    private val singleLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            isGpsActive = true

            val speedKmh = calculateSpeed(location)
            currentGpsSpeed = speedKmh

            val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else "未知"
            lastLocationDesc = String.format(
                "經度: %.4f | 緯度: %.4f (精確度: %s | 來源: %s)",
                location.longitude,
                location.latitude,
                accuracy,
                location.provider ?: "GPS"
            )

            // 同步給神盾資料快取
            val curData = SpeedTrapListenerService.latestData
            val updatedData = curData.copy(currentSpeed = speedKmh)
            SpeedTrapListenerService.latestData = updatedData

            // 推送更新給 Widget
            appContext?.let { ctx ->
                CarHudWidgetProvider.updateWidgetData(
                    context = ctx,
                    speedLimit = updatedData.speedLimit,
                    distMeters = updatedData.distanceMeters,
                    currentSpeed = speedKmh,
                    camType = updatedData.camType
                )
            }

            // 觸發全域與監聽回呼
            SpeedTrapListenerService.onDataUpdated?.invoke(updatedData)
            onGpsSpeedUpdated?.invoke(speedKmh, lastLocationDesc)

            // 🚗 方案 6.1: 車載返家即時到站 (Car-to-Living-Room ETA) 自動運算與通報
            appContext?.let { ctx ->
                CarHomeEtaManager.onLocationUpdated(ctx, location, speedKmh)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { isGpsActive = true }
        override fun onProviderDisabled(provider: String) { isGpsActive = false }
    }

    /**
     * 計算即時車速：
     * 1. 優先使用原生 location.speed (m/s * 3.6)
     * 2. 若車機晶片未傳回 speed (location.hasSpeed() == false)，採用相鄰經緯度座標差與時間差推算真實速度
     */
    private fun calculateSpeed(location: Location): Int {
        // 原生速度判定
        if (location.hasSpeed() && location.speed > 0f) {
            lastSpeedSource = "晶片原生速度"
            return (location.speed * 3.6f).toInt()
        }

        // 經緯度微分推算備援
        val prev = lastLocation
        val prevTime = lastLocationTimeMs
        val now = if (location.time > 0) location.time else System.currentTimeMillis()

        lastLocation = location
        lastLocationTimeMs = now

        if (prev != null && prevTime > 0) {
            val dtSec = (now - prevTime) / 1000.0
            if (dtSec in 0.3..6.0) {
                val distMeters = location.distanceTo(prev)
                val calcSpeed = (distMeters / dtSec) * 3.6
                // 濾除靜止時的 GPS 座標抖動 (若車速小於 2km/h 視為 0)
                if (calcSpeed in 2.0..250.0) {
                    lastSpeedSource = "座標微分推算"
                    return calcSpeed.toInt()
                }
            }
        }

        lastSpeedSource = "靜止或無速度"
        return 0
    }

    /**
     * 啟動車載 GPS 即時測速監聽 (支援 GPS + Network 雙軌容錯)
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        appContext = context.applicationContext

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            Log.w(TAG, "未取得 GPS 定位權限，無法啟用車速監聽")
            return
        }

        if (isListening) return

        try {
            locationManager = appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                Log.e(TAG, "無法取得 LocationManager")
                return
            }

            // 1. 註冊 GPS_PROVIDER (高頻率 500ms 刷新)
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    singleLocationListener,
                    Looper.getMainLooper()
                )
                Log.i(TAG, "✓ GPS_PROVIDER 已啟動監聽 (每 500ms)")
            } else {
                Log.w(TAG, "車機 GPS_PROVIDER 尚未開啟")
            }

            // 2. 註冊 NETWORK_PROVIDER (輔助定位與室內/隧道備援)
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    singleLocationListener,
                    Looper.getMainLooper()
                )
                Log.i(TAG, "✓ NETWORK_PROVIDER 已啟動輔助監聽")
            }

            // 3. 嘗試讀取最後一次已知座標以快速就緒
            val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                singleLocationListener.onLocationChanged(lastGps)
            }

            isListening = true
            Log.i(TAG, "✓ 原生 GPS 即時車速引擎已全面啟動")
        } catch (e: Exception) {
            Log.e(TAG, "啟動 GPS 失敗: ${e.message}")
        }
    }

    /**
     * 停止監聽
     */
    fun stopListening() {
        if (isListening && locationManager != null) {
            try {
                locationManager?.removeUpdates(singleLocationListener)
                isListening = false
                Log.i(TAG, "已停止 GPS 車速監聽")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 供測試按鈕模擬車速跳動
     */
    fun simulateSpeed(speedKmh: Int) {
        currentGpsSpeed = speedKmh
        lastSpeedSource = "模擬測試訊號"
        lastLocationDesc = "手動模擬測試中 ($speedKmh km/h)"

        val curData = SpeedTrapListenerService.latestData
        val updatedData = curData.copy(currentSpeed = speedKmh)
        SpeedTrapListenerService.latestData = updatedData

        appContext?.let { ctx ->
            CarHudWidgetProvider.updateWidgetData(
                context = ctx,
                speedLimit = updatedData.speedLimit,
                distMeters = updatedData.distanceMeters,
                currentSpeed = speedKmh,
                camType = updatedData.camType
            )
        }

        SpeedTrapListenerService.onDataUpdated?.invoke(updatedData)
        onGpsSpeedUpdated?.invoke(speedKmh, lastLocationDesc)
    }
}
