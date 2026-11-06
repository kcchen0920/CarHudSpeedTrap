package com.carhud.speedtrap

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 車載 GPS 高頻即時測速引擎 (全方位融合 GPS、Passive 與 Network 雙軌定位)
 * 解決車機晶片未提供 location.hasSpeed() 的 0 時速問題與快速回呼重設座標導致時速不變之缺陷。
 * 自動掛鉤 LocationManager.PASSIVE_PROVIDER，無縫承接神盾 App 產生的所有即時 GPS 數據！
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

    // 方案六：車載動態階梯式節能溫控採樣檔位 (Adaptive Thermal Throttling)
    enum class GpsSamplingTier(val minIntervalMs: Long, val label: String) {
        IDLE_COOLING(2500L, "靜止怠速溫控節能 (2.5s)"),
        CITY_CRUISE(1000L, "市區巡航 (1.0s)"),
        HIGH_SPEED(400L, "高速全開 (400ms)")
    }

    @Volatile
    var currentSamplingTier: GpsSamplingTier = GpsSamplingTier.CITY_CRUISE

    private var lastProcessElapsed: Long = 0L
    private var stationaryStartElapsed: Long = 0L

    private var appContext: Context? = null
    private var locationManager: LocationManager? = null
    private var isGpsRegistered = false
    private var isPassiveRegistered = false
    private var isNetworkRegistered = false
    private var isNmeaRegistered = false

    // 專屬於 GPS 差分計算的最後參考座標與硬體單調時間戳記 (SystemClock.elapsedRealtime)
    private var lastGpsLocation: Location? = null
    private var lastGpsLocationElapsed: Long = 0L

    // 記錄最後一次收到 GPS/NMEA 回呼的單調時間戳記
    private var lastGpsCallbackElapsed: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // 逾時安全看門狗與自癒巡檢：
    // 1. 若超過 4.5 秒無任何定位訊號且車速大於 0，自動將時速歸零，避免儀表卡死在非零時速
    // 2. 開機硬體冷啟動延遲自癒：若超過 5 秒未收到任何 GPS/NMEA 回呼，代表開機初期註冊受硬體未通電影響失效，自動解除舊監聽並重新掛鉤！
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()

            // 1. 逾時歸零防護
            if (lastGpsCallbackElapsed > 0L && (now - lastGpsCallbackElapsed) > 4500L) {
                if (currentGpsSpeed > 0) {
                    currentGpsSpeed = 0
                    lastSpeedSource = "訊號靜止 (逾時歸零)"
                    val curData = SpeedTrapListenerService.latestData
                    val updated = curData.copy(currentSpeed = 0)
                    SpeedTrapListenerService.latestData = updated
                    SpeedTrapListenerService.dispatchDataUpdated(updated)
                    dispatchGpsSpeed(0, lastLocationDesc)

                    appContext?.let { ctx ->
                        CarHudWidgetProvider.updateWidgetData(
                            context = ctx,
                            speedLimit = updated.speedLimit,
                            distMeters = updated.distanceMeters,
                            currentSpeed = 0,
                            camType = updated.camType
                        )
                    }
                }
            }

            // 2. 開機硬體延遲自癒：若超過 5 秒未收到任何實體訊號，強制重設狀態並重新掛鉤，擊破死監聽
            val isSilent = lastGpsCallbackElapsed == 0L || (now - lastGpsCallbackElapsed) > 5000L
            if (isSilent) {
                isGpsActive = false
                isGpsRegistered = false
                isPassiveRegistered = false
                isNetworkRegistered = false
                isNmeaRegistered = false
                appContext?.let { ctx ->
                    tryRegisterProviders(ctx)
                }
            }

            mainHandler.postDelayed(this, 3500L)
        }
    }

    // 供 UI / MainActivity / HUD 監聽 GPS 狀態更新 (支援多觀察者防護)
    var onGpsSpeedUpdated: ((speed: Int, desc: String) -> Unit)? = null

    private val speedListeners = java.util.concurrent.CopyOnWriteArrayList<(speed: Int, desc: String) -> Unit>()

    fun addSpeedListener(listener: (speed: Int, desc: String) -> Unit) {
        if (!speedListeners.contains(listener)) speedListeners.add(listener)
    }

    fun removeSpeedListener(listener: (speed: Int, desc: String) -> Unit) {
        speedListeners.remove(listener)
    }

    private fun dispatchGpsSpeed(speed: Int, desc: String) {
        onGpsSpeedUpdated?.invoke(speed, desc)
        speedListeners.forEach {
            try { it.invoke(speed, desc) } catch (e: Exception) { Log.w(TAG, "listener error: ${e.message}") }
        }
    }

    /**
     * 專用 GPS 與 Passive 測速監聽器：專責處理高頻即時車速與神盾資料連動
     */
    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location, isHighAccuracy = true)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { isGpsActive = true }
        override fun onProviderDisabled(provider: String) { isGpsActive = false }
    }

    /**
     * 獨立 Network 輔助監聽器：室內、地下室或隧道備援
     */
    private val networkLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val nowElapsed = SystemClock.elapsedRealtime()
            // 若 4 秒內已有高精度 GPS/Passive 訊號，以 GPS 為主
            if (isGpsActive && (nowElapsed - lastGpsCallbackElapsed) < 4000L) {
                return
            }
            handleLocationUpdate(location, isHighAccuracy = false)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun handleLocationUpdate(location: Location, isHighAccuracy: Boolean) {
        isGpsActive = true
        val nowElapsed = SystemClock.elapsedRealtime()
        lastGpsCallbackElapsed = nowElapsed

        // 方案六：車載階梯式採樣檔位動態評估 (防夏天車機熱當降頻)
        val tier = when {
            currentGpsSpeed == 0 -> {
                if (stationaryStartElapsed == 0L) stationaryStartElapsed = nowElapsed
                if (nowElapsed - stationaryStartElapsed > 5000L) GpsSamplingTier.IDLE_COOLING else GpsSamplingTier.CITY_CRUISE
            }
            currentGpsSpeed in 1..55 -> {
                stationaryStartElapsed = 0L
                GpsSamplingTier.CITY_CRUISE
            }
            else -> {
                stationaryStartElapsed = 0L
                GpsSamplingTier.HIGH_SPEED
            }
        }
        currentSamplingTier = tier

        // 靜止防燙節流：若處於靜止怠速檔且距上次運算小於 2.5 秒，跳過重複座標位移推算與派發，使 CPU 休息降溫
        if (tier == GpsSamplingTier.IDLE_COOLING && (nowElapsed - lastProcessElapsed) < tier.minIntervalMs) {
            return
        }
        lastProcessElapsed = nowElapsed

        val speedKmh = calculateSpeed(location)
        currentGpsSpeed = speedKmh

        val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()}m" else "未知"
        lastLocationDesc = String.format(
            "經度: %.4f | 緯度: %.4f (精確度: %s | 來源: %s)",
            location.longitude,
            location.latitude,
            accuracy,
            location.provider ?: if (isHighAccuracy) "GPS" else "Network"
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

        // 觸發全域與多監聽回呼 (即時更新懸浮球與主畫面)
        SpeedTrapListenerService.dispatchDataUpdated(updatedData)
        dispatchGpsSpeed(speedKmh, lastLocationDesc)

        // 車載返家即時到站 (Car-to-Living-Room ETA) 自動運算與通報
        appContext?.let { ctx ->
            CarHomeEtaManager.onLocationUpdated(ctx, location, speedKmh)
        }
    }

    /**
     * 計算即時車速：
     * 1. 優先使用晶片原生 location.speed (m/s * 3.6)
     * 2. 若車機晶片未傳回 speed (location.hasSpeed() == false)，採用硬體單調時脈 (SystemClock.elapsedRealtime)
     *    相鄰經緯度座標差與毫秒時間差推算真實速度。
     * 3. 解決快速連續回呼 (< 300ms) 頻繁刷新參考點導致無法累積時間差的重大缺陷！
     */
    private fun calculateSpeed(location: Location): Int {
        // 1. 原生速度判定 (若硬體晶片有提供大於 0 的速度)
        if (location.hasSpeed() && location.speed > 0f) {
            lastSpeedSource = "晶片原生速度"
            val s = (location.speed * 3.6f).toInt()
            lastGpsLocation = location
            lastGpsLocationElapsed = SystemClock.elapsedRealtime()
            return s
        }

        // 2. 經緯度微分推算備援 (使用硬體單調時鐘)
        val prev = lastGpsLocation
        val prevElapsed = lastGpsLocationElapsed
        val nowElapsed = SystemClock.elapsedRealtime()

        if (prev == null || prevElapsed == 0L) {
            lastGpsLocation = location
            lastGpsLocationElapsed = nowElapsed
            return if (location.hasSpeed()) (location.speed * 3.6f).toInt() else 0
        }

        val dtSec = (nowElapsed - prevElapsed) / 1000.0

        // 核心關鍵修正：若時間差小於 300ms，保留前一個參考座標 prev，不重設參考點！避免連續快速回呼導致 dtSec 永遠 < 0.3s
        if (dtSec < 0.3) {
            return if (location.hasSpeed() && location.speed > 0f) (location.speed * 3.6f).toInt() else currentGpsSpeed
        }

        // 當時間差達到 300ms ~ 12s 時，進行座標位移距離計算
        if (dtSec in 0.3..12.0) {
            val distMeters = location.distanceTo(prev)
            val calcSpeed = (distMeters / dtSec) * 3.6

            // 更新參考座標與單調時間
            lastGpsLocation = location
            lastGpsLocationElapsed = nowElapsed

            // 濾除靜止時的 GPS 座標漂移 (若車速小於 2.0km/h 視為 0)
            if (calcSpeed in 2.0..260.0) {
                lastSpeedSource = "座標位移推算 (${distMeters.toInt()}m/${String.format("%.1f", dtSec)}s)"
                return calcSpeed.toInt()
            } else if (calcSpeed < 2.0) {
                lastSpeedSource = "靜止 (0 km/h)"
                return 0
            }
        }

        // 超過 12 秒代表訊號中斷過久，重設參考點
        lastGpsLocation = location
        lastGpsLocationElapsed = nowElapsed
        lastSpeedSource = if (location.hasSpeed()) "晶片原生靜止 (0 km/h)" else "靜止或無速度"
        return 0
    }

    /**
     * 啟動車載 GPS 即時測速監聽 (全時 500ms 高頻刷新，掛鉤 GPS、Passive 與 Network)
     */
    fun startListening(context: Context) {
        appContext = context.applicationContext
        tryRegisterProviders(context)
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.post(watchdogRunnable)
    }

    /**
     * 核心提供者註冊引擎 (支援車機冷啟動延遲重試與開機自癒)
     */
    @SuppressLint("MissingPermission")
    private fun tryRegisterProviders(context: Context) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "未取得 GPS 定位權限，無法啟用車速監聽")
            return
        }

        try {
            if (locationManager == null) {
                locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }
            val lm = locationManager
            if (lm == null) {
                Log.e(TAG, "無法取得 LocationManager")
                return
            }

            // 0. 清理可能卡死或無效的舊監聽 (避免重複註冊或資源洩漏)
            try {
                lm.removeUpdates(gpsLocationListener)
                lm.removeUpdates(networkLocationListener)
            } catch (ignored: Exception) {}

            // 1. 註冊 GPS_PROVIDER (全時 500ms 高頻率、0 米無死角刷新)
            // 核心關鍵修正：不阻擋在 isProviderEnabled，車機通電開機時立即掛鉤，待晶片啟動直接生效
            if (hasFine && !isGpsRegistered) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                    )
                    isGpsRegistered = true
                    Log.i(TAG, "✓ GPS_PROVIDER 已啟動全時監聽 (每 500ms)")
                } catch (e: Exception) {
                    Log.w(TAG, "註冊 GPS_PROVIDER 暫未成功 (待看門狗自動重試): ${e.message}")
                }
            }

            // 2. 核心增強：註冊 PASSIVE_PROVIDER (承接神盾、Google Maps 與導航王產生的所有定位回呼)
            if (hasFine && !isPassiveRegistered) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        500L,
                        0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                    )
                    isPassiveRegistered = true
                    Log.i(TAG, "✓ PASSIVE_PROVIDER 已掛鉤 (即時承接神盾 App 座標與車速)")
                } catch (e: Exception) {
                    Log.w(TAG, "註冊 PASSIVE_PROVIDER 暫未成功: ${e.message}")
                }
            }

            // 3. 註冊 FUSED 提供者 (若車機硬體有支援)
            if (hasFine) {
                try {
                    lm.requestLocationUpdates(
                        "fused",
                        500L,
                        0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                    )
                } catch (ignored: Exception) {}
            }

            // 4. 核心硬體直讀：掛鉤 NMEA 0183 原始衛星數據流 ($GPRMC, $GPVTG, $GNRMC)
            // 車機硬體 GPS 晶片無論 Android LocationListener 是否回傳，皆會以 1Hz~5Hz 直吐 NMEA 語句
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hasFine && !isNmeaRegistered) {
                try {
                    lm.addNmeaListener(OnNmeaMessageListener { message: String?, _: Long ->
                        message?.let { handleNmeaMessage(it) }
                    }, Handler(Looper.getMainLooper()))
                    isNmeaRegistered = true
                    Log.i(TAG, "✓ NMEA 衛星底層數據流已直接掛鉤 (免經中介層直接獲取硬體車速)")
                } catch (e: Exception) {
                    Log.w(TAG, "掛鉤 NMEA 異常: ${e.message}")
                }
            }

            // 5. 註冊 NETWORK_PROVIDER (輔助定位與室內/隧道備援)
            if (!isNetworkRegistered) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1500L,
                        0f,
                        networkLocationListener,
                        Looper.getMainLooper()
                    )
                    isNetworkRegistered = true
                    Log.i(TAG, "✓ NETWORK_PROVIDER 已啟動輔助監聽")
                } catch (e: Exception) {
                    Log.w(TAG, "註冊 NETWORK_PROVIDER 異常: ${e.message}")
                }
            }

            // 6. 嘗試讀取最後已知座標以快速就緒
            val lastLoc = try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                null
            }

            if (lastLoc != null && lastGpsLocation == null) {
                gpsLocationListener.onLocationChanged(lastLoc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "註冊 GPS 提供者異常: ${e.message}")
        }
    }

    /**
     * 解析 NMEA 0183 原始衛星數據流中的真實車速
     * 支援 $GPRMC, $GNRMC (海浬 knots 轉 km/h) 與 $GPVTG, $GNVTG (直接 km/h)
     */
    private fun handleNmeaMessage(message: String) {
        if (!message.startsWith("$")) return
        try {
            val parts = message.split(",")
            val tag = parts[0].uppercase(java.util.Locale.ROOT)
            var speedKmh = -1f

            // 1. $GPRMC / $GNRMC / $BDRMC
            // 格式: $GPRMC,hhmmss.ss,A,lat,a,lon,a,speed(knots),track,date...
            if ((tag.endsWith("RMC")) && parts.size > 7) {
                val status = parts[2]
                if (status.equals("A", ignoreCase = true)) {
                    val knots = parts[7].toFloatOrNull()
                    if (knots != null) {
                        speedKmh = knots * 1.852f
                    }
                }
            }
            // 2. $GPVTG / $GNVTG / $BDVTG
            // 格式: $GPVTG,track,T,track,M,speed(knots),N,speed(km/h),K...
            else if ((tag.endsWith("VTG")) && parts.size > 7) {
                val kmh = parts[7].toFloatOrNull()
                if (kmh != null) {
                    speedKmh = kmh
                }
            }

            if (speedKmh >= 0f) {
                isGpsActive = true
                lastGpsCallbackElapsed = SystemClock.elapsedRealtime()
                val finalSpeed = if (speedKmh < 1.5f) 0 else speedKmh.toInt()

                if (finalSpeed != currentGpsSpeed || (finalSpeed > 0 && currentGpsSpeed == 0)) {
                    currentGpsSpeed = finalSpeed
                    lastSpeedSource = "NMEA 硬體直讀 ($finalSpeed km/h)"
                    lastLocationDesc = "NMEA 原始硬體訊號連線中 ($tag)"

                    val curData = SpeedTrapListenerService.latestData
                    val updated = curData.copy(currentSpeed = finalSpeed)
                    SpeedTrapListenerService.latestData = updated
                    SpeedTrapListenerService.dispatchDataUpdated(updated)
                    dispatchGpsSpeed(finalSpeed, lastLocationDesc)

                    appContext?.let { ctx ->
                        CarHudWidgetProvider.updateWidgetData(
                            context = ctx,
                            speedLimit = updated.speedLimit,
                            distMeters = updated.distanceMeters,
                            currentSpeed = finalSpeed,
                            camType = updated.camType
                        )
                    }
                }
            }
        } catch (ignored: Exception) {}
    }

    /**
     * 停止監聽
     */
    fun stopListening() {
        mainHandler.removeCallbacks(watchdogRunnable)
        val lm = locationManager
        if (lm != null) {
            try {
                if (isGpsRegistered || isPassiveRegistered) {
                    lm.removeUpdates(gpsLocationListener)
                    isGpsRegistered = false
                    isPassiveRegistered = false
                }
                if (isNetworkRegistered) {
                    lm.removeUpdates(networkLocationListener)
                    isNetworkRegistered = false
                }
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
        isGpsActive = true
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

        SpeedTrapListenerService.dispatchDataUpdated(updatedData)
        dispatchGpsSpeed(speedKmh, lastLocationDesc)
    }
}
