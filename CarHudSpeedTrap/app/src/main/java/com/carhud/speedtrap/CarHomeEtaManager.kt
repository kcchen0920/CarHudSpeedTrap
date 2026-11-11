package com.carhud.speedtrap

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 方案 6.1：車載—客廳即時抵達聯動 (Car-to-Living-Room ETA) 管理器
 * 當車輛進入住家 5 公里範圍並朝住家前進時，透過 ntfy.sh 免費通道即時通知客廳電視 Chromebox-TV-Home。
 */
object CarHomeEtaManager {

    private const val TAG = "CarHomeEtaManager"
    const val PREF_NAME = "carhud_prefs"
    const val KEY_HOME_ETA_ENABLED = "home_eta_enabled"
    const val KEY_HOME_LAT = "home_lat"
    const val KEY_HOME_LNG = "home_lng"
    const val KEY_HOME_CHANNEL = "home_channel"
    const val DEFAULT_CHANNEL = "kcchen-family-livingroom"

    private const val APPROACHING_THRESHOLD_METERS = 5_000f // 5 km 接近門檻
    private const val ARRIVED_THRESHOLD_METERS = 500f        // 500 m 到家門檻
    private const val RESET_THRESHOLD_METERS = 8_000f        // 8 km 重設行程

    private var hasNotifiedApproaching = false
    private var hasNotifiedArrived = false
    private var lastDistanceMeters: Float = -1f
    private var lastNotifyTimeMs: Long = 0L

    private val pushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarEtaPushExecutor").apply { isDaemon = true }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HOME_ETA_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HOME_ETA_ENABLED, enabled).apply()
    }

    fun getHomeLocation(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lat = prefs.getFloat(KEY_HOME_LAT, 24.965467f).toDouble()
        val lng = prefs.getFloat(KEY_HOME_LNG, 121.237283f).toDouble()
        if (lat == 0.0 && lng == 0.0) {
            return Pair(24.965467, 121.237283)
        }
        return Pair(lat, lng)
    }

    fun setHomeLocation(context: Context, lat: Double, lng: Double) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_HOME_LAT, lat.toFloat())
            .putFloat(KEY_HOME_LNG, lng.toFloat())
            .apply()
        Log.i(TAG, "已設定住家座標: $lat, $lng")
    }

    fun getChannel(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOME_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL
    }

    fun setChannel(context: Context, channel: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HOME_CHANNEL, channel.trim()).apply()
    }

    fun onLocationUpdated(context: Context, currentLocation: Location, currentSpeedKmh: Int) {
        if (!isEnabled(context)) return
        val homeCoords = getHomeLocation(context) ?: return

        val homeLoc = Location("Home").apply {
            latitude = homeCoords.first
            longitude = homeCoords.second
        }

        val distanceMeters = currentLocation.distanceTo(homeLoc)
        val now = System.currentTimeMillis()

        // 離開 8 公里以外，重設行程旗標
        if (distanceMeters > RESET_THRESHOLD_METERS) {
            if (hasNotifiedApproaching || hasNotifiedArrived) {
                Log.d(TAG, "車輛已遠離住家 (${distanceMeters.toInt()}m)，重設單次返家狀態")
                hasNotifiedApproaching = false
                hasNotifiedArrived = false
            }
            lastDistanceMeters = distanceMeters
            return
        }

        // 判斷是否正朝著住家靠近 (向量距離縮小，允許 150m 道路繞路容錯)
        val isMovingCloser = lastDistanceMeters < 0 || distanceMeters < (lastDistanceMeters + 150f)
        lastDistanceMeters = distanceMeters

        // 1. 觸發「即將抵達 (接近中)」事件：進入 5 公里且朝住家前進
        if (distanceMeters <= APPROACHING_THRESHOLD_METERS && distanceMeters > ARRIVED_THRESHOLD_METERS) {
            if (!hasNotifiedApproaching && isMovingCloser) {
                // 冷卻防抖 (30 分鐘內不重複發送接近通知)
                if (now - lastNotifyTimeMs > 1800_000L) {
                    val distKm = distanceMeters / 1000f
                    val effectiveSpeed = max(currentSpeedKmh.toFloat(), 25f)
                    val etaMinutes = ((distKm / effectiveSpeed) * 60f).roundToInt().coerceAtLeast(1)

                    Log.i(TAG, "🚗 車輛返家接近中！距離: ${distKm}km, 預估 ETA: $etaMinutes 分鐘")
                    sendEtaPush(
                        context = context,
                        event = "car_approaching",
                        distanceKm = (distKm * 10).roundToInt() / 10f,
                        speedKmh = currentSpeedKmh,
                        etaMinutes = etaMinutes
                    )
                    hasNotifiedApproaching = true
                    lastNotifyTimeMs = now
                }
            }
        }

        // 2. 觸發「已抵達家門」事件：進入 500 公尺以內
        if (distanceMeters <= ARRIVED_THRESHOLD_METERS) {
            if (hasNotifiedApproaching && !hasNotifiedArrived) {
                Log.i(TAG, "🏡 車輛已抵達家門口！")
                sendEtaPush(
                    context = context,
                    event = "car_arrived",
                    distanceKm = (distanceMeters / 1000f * 10).roundToInt() / 10f,
                    speedKmh = currentSpeedKmh,
                    etaMinutes = 0
                )
                hasNotifiedArrived = true
            }
        }
    }

    fun sendTestPush(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        sendEtaPush(
            context = context,
            event = "car_approaching",
            distanceKm = 8.5f,
            speedKmh = 50,
            etaMinutes = 10,
            onResult = onResult
        )
    }

    private fun sendEtaPush(
        context: Context,
        event: String,
        distanceKm: Float,
        speedKmh: Int,
        etaMinutes: Int,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val channel = getChannel(context)
        val endpoint = "https://ntfy.sh"
        val homeCoords = getHomeLocation(context)
        val homeNavUrl = "https://www.google.com/maps/dir/?api=1&destination=${homeCoords.first},${homeCoords.second}"
        val dashUrl = "http://192.168.50.166:8088"
        val snoozeUrl = "http://192.168.50.166:8088/api/tag/snooze?tag_id=car_hud&minutes=30"

        pushExecutor.execute {
            try {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val isArrived = (event == "car_arrived")
                val title = if (isArrived) "🏡 爸爸已抵達家門口！" else "🚗 爸爸正在回家路上 (ETA: ${etaMinutes}分)"
                val message = if (isArrived) {
                    "🕒 抵達時間：$timeStr\n📍 當前位置：已抵達住家 (進入 500m 迎賓範圍)\n🚗 行駛狀態：車輛已安全返抵，準備停妥熄火\n🚪 貼心提醒：請家人準備開門迎接～"
                } else {
                    "🕒 估算時間：$timeStr\n⏱️ 預估抵達：${etaMinutes} 分鐘後 (ETA)\n📍 距離住家：約 ${distanceKm} km\n⚡ 當前車速：${speedKmh} km/h (行駛中)\n💡 貼心提醒：請家人注意準備～"
                }

                val actionsArray = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("action", "view")
                        put("label", "🗺️ 查看住家位置")
                        put("url", homeNavUrl)
                        put("clear", true)
                    })
                    put(JSONObject().apply {
                        put("action", "view")
                        put("label", "📊 家庭面板")
                        put("url", dashUrl)
                        put("clear", true)
                    })
                    put(JSONObject().apply {
                        put("action", "http")
                        put("label", "🔕 靜音 30 分鐘")
                        put("url", snoozeUrl)
                        put("method", "POST")
                        put("clear", true)
                    })
                }

                val tagsArray = org.json.JSONArray().apply {
                    if (isArrived) {
                        put("car")
                        put("house")
                        put("tada")
                    } else {
                        put("car")
                        put("clock")
                        put("hourglass_flowing_sand")
                    }
                }

                val payload = JSONObject().apply {
                    put("topic", channel)
                    put("title", title)
                    put("message", message)
                    put("priority", 4) // Priority 4: Pops out heads-up banner window across all states, obeys mute/DND
                    put("tags", tagsArray)
                    put("click", homeNavUrl)
                    put("actions", actionsArray)
                }

                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                conn.disconnect()

                val success = responseCode in 200..299
                Log.i(TAG, "ntfy.sh 推播發送結果: code=$responseCode, success=$success")

                Handler(Looper.getMainLooper()).post {
                    onResult?.invoke(success, if (success) "推播發送成功 (HTTP $responseCode)" else "推播失敗: HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ntfy.sh 發送例外: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onResult?.invoke(false, "網路連線異常: ${e.message}")
                }
            }
        }
    }
}
