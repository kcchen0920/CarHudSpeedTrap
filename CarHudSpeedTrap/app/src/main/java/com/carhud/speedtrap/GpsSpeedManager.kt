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

object GpsSpeedManager {

    private const val TAG = "GpsSpeedManager"

    @Volatile
    var currentGpsSpeed: Int = 0

    @Volatile
    var isGpsActive: Boolean = false

    private var locationManager: LocationManager? = null
    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            isGpsActive = true
            // Android 原生 location.speed 單位為 m/s，乘以 3.6 轉為 km/h
            val speedKmh = if (location.hasSpeed()) {
                (location.speed * 3.6f).toInt()
            } else {
                0
            }

            if (speedKmh != currentGpsSpeed) {
                currentGpsSpeed = speedKmh
                Log.d(TAG, "GPS 車速更新: $currentGpsSpeed km/h")

                // 即時刷新 Widget
                val data = SpeedTrapListenerService.latestData
                val updatedData = data.copy(currentSpeed = currentGpsSpeed)
                SpeedTrapListenerService.latestData = updatedData

                // 觸發廣播與回呼
                CarHudWidgetProvider.updateWidgetData(
                    context = null ?: return, // 若被 static 呼叫由外部 context 處理
                    speedLimit = updatedData.speedLimit,
                    distMeters = updatedData.distanceMeters,
                    currentSpeed = currentGpsSpeed,
                    camType = updatedData.camType
                )
                SpeedTrapListenerService.onDataUpdated?.invoke(updatedData)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { isGpsActive = true }
        override fun onProviderDisabled(provider: String) { isGpsActive = false }
    }

    /**
     * 啟動車載 GPS 即時測速監聽
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context) {
        if (isListening) return

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            Log.w(TAG, "未取得 GPS 定位權限，無法啟用原生車速監聽")
            return
        }

        try {
            locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            if (!isGpsEnabled) {
                Log.w(TAG, "車機 GPS 尚未開啟")
            }

            // 每 500ms，距離 0 米觸發一次高精度更新
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        isGpsActive = true
                        val speedKmh = if (location.hasSpeed()) {
                            (location.speed * 3.6f).toInt()
                        } else {
                            0
                        }

                        currentGpsSpeed = speedKmh

                        val data = SpeedTrapListenerService.latestData
                        val updatedData = data.copy(currentSpeed = speedKmh)
                        SpeedTrapListenerService.latestData = updatedData

                        CarHudWidgetProvider.updateWidgetData(
                            context = context,
                            speedLimit = updatedData.speedLimit,
                            distMeters = updatedData.distanceMeters,
                            currentSpeed = speedKmh,
                            camType = updatedData.camType
                        )
                        SpeedTrapListenerService.onDataUpdated?.invoke(updatedData)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) { isGpsActive = true }
                    override fun onProviderDisabled(p: String) { isGpsActive = false }
                },
                Looper.getMainLooper()
            )

            isListening = true
            Log.i(TAG, "✓ 原生 GPS 即時車速引擎已啟動 (每 0.5s 更新)")
        } catch (e: Exception) {
            Log.e(TAG, "啟動 GPS 失敗: ${e.message}")
        }
    }

    fun stopListening() {
        if (isListening && locationManager != null) {
            try {
                locationManager?.removeUpdates(locationListener)
                isListening = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
