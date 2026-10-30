package com.carhud.speedtrap

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class SpeedTrapData(
    val speedLimit: Int,
    val distanceMeters: Int,
    val currentSpeed: Int,
    val camType: String,
    val rawText: String = ""
)

class SpeedTrapListenerService : NotificationListenerService() {

    companion object {
        const val TAG = "SpeedTrapListener"
        const val SPEEDTRAP_PACKAGE = "com.locallife.speedtrap"

        // 服務是否成功連線至系統
        @Volatile
        var isConnected: Boolean = false

        // 記錄最近一次接收到通知的時間戳記（供保活與運行偵測判定）
        @Volatile
        var lastNotificationTimeMillis: Long = 0L

        // 記錄最近一次接收到的除錯資訊
        @Volatile
        var lastNotificationTime: String = "尚未收到任何通知"
        @Volatile
        var lastPackageName: String = "無"
        @Volatile
        var lastRawText: String = "無"

        // 最新快取的測速資料 (初始速限為 0，無相機訊號時顯示 --，不鎖定 60)
        @Volatile
        var latestData: SpeedTrapData = SpeedTrapData(
            speedLimit = 0,
            distanceMeters = 0,
            currentSpeed = 0,
            camType = "🟢 服務就緒，等待測速訊號"
        )

        // 保存當前連線中的 Listener 實例
        var currentInstance: SpeedTrapListenerService? = null

        // 供 Activity / Overlay 註冊回呼
        var onDataUpdated: ((SpeedTrapData) -> Unit)? = null
        var onDebugInfoUpdated: (() -> Unit)? = null

        /**
         * 判定神盾是否已在運行中（多維度檢查：5分鐘內通知心跳、作用中常駐狀態列通知）
         */
        fun isSpeedTrapActive(context: Context): Boolean {
            val now = System.currentTimeMillis()
            // 1. 若 5 分鐘內曾收到神盾通知，判定為背景運行中
            if (lastNotificationTimeMillis > 0 && (now - lastNotificationTimeMillis) < 300_000L) {
                return true
            }

            // 2. 檢查作用中狀態列通知
            val instance = currentInstance
            if (isConnected && instance != null) {
                try {
                    val active = instance.activeNotifications
                    if (active != null) {
                        for (sbn in active) {
                            if (sbn != null && instance.isSpeedTrapPackage(sbn.packageName)) {
                                return true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "檢查作用中通知失敗: ${e.message}")
                }
            }
            return false
        }

        /**
         * 檢查車機是否安裝了神盾測速照相 (支援模糊比對與自訂綁定)
         */
        fun checkSpeedTrapInstalled(context: Context): Boolean {
            return SpeedTrapAutoLauncher.findSpeedTrapPackage(context) != null
        }

        /**
         * 強制重新連線 NotificationListenerService
         */
        fun forceRebind(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val comp = ComponentName(context, SpeedTrapListenerService::class.java)
                    val pm = context.packageManager
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        comp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "已觸發 NotificationListener 強制重連")
                }
            } catch (e: Exception) {
                Log.e(TAG, "forceRebind 失敗: ${e.message}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        currentInstance = this
        Log.i(TAG, "✓ NotificationListenerService 成功連線！")

        // 服務啟動時同步啟用高頻 GPS 測速引擎
        GpsSpeedManager.startListening(applicationContext)

        // 核心：連線時自動確保 HUD 懸浮球常駐
        TopHudOverlayService.startIfEnabled(applicationContext)

        // 核心亮點：立即掃描當前系統作用中通知（若神盾已在背景執行，立即解析無需等待新事件）
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn != null && isSpeedTrapPackage(sbn.packageName)) {
                    processSpeedTrapNotification(sbn)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "讀取既有作用中通知失敗: ${e.message}")
        }

        onDebugInfoUpdated?.invoke()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        currentInstance = null
        Log.w(TAG, "⚠ NotificationListenerService 已斷線！")
        onDebugInfoUpdated?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, SpeedTrapListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (!isSpeedTrapPackage(sbn.packageName)) return
        // 核心保活：收到神盾測速通知時，若 HUD 懸浮球未啟動，自動拉起常駐
        TopHudOverlayService.startIfEnabled(applicationContext)
        processSpeedTrapNotification(sbn)
    }

    fun isSpeedTrapPackage(pkgName: String?): Boolean {
        if (pkgName.isNullOrEmpty()) return false
        val prefs = getSharedPreferences("carhud_prefs", Context.MODE_PRIVATE)
        val customPkg = prefs.getString(SpeedTrapAutoLauncher.KEY_CUSTOM_SPEEDTRAP_PKG, null)
        if (!customPkg.isNullOrEmpty() && pkgName.equals(customPkg, ignoreCase = true)) {
            return true
        }
        return pkgName.contains("speedtrap", ignoreCase = true) ||
                pkgName.contains("locallife", ignoreCase = true) ||
                pkgName.contains("radardroid", ignoreCase = true) ||
                pkgName.contains("naviking", ignoreCase = true) ||
                pkgName.contains("polnav", ignoreCase = true) ||
                pkgName == SPEEDTRAP_PACKAGE
    }

    private fun processSpeedTrapNotification(sbn: StatusBarNotification) {
        val pkgName = sbn.packageName ?: ""
        lastNotificationTimeMillis = System.currentTimeMillis()

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lastNotificationTime = timeFormat.format(Date())
        lastPackageName = pkgName

        // 提取所有通知文字 (CharSequence 防禦 null 與各類型 extra 欄位)
        val extras = sbn.notification.extras
        val contentBuilder = StringBuilder()

        if (extras != null) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""

            contentBuilder.append(title).append(" ")
                .append(text).append(" ")
                .append(subText).append(" ")
                .append(bigText).append(" ")
                .append(infoText).append(" ")
                .append(summaryText).append(" ")

            // 遍歷所有 extras 欄位防禦自訂 key 與數值型別
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value != null) {
                    if (value is CharSequence && value.isNotEmpty()) {
                        contentBuilder.append(value).append(" ")
                    } else if (value is Number) {
                        val keyLower = key.lowercase(Locale.ROOT)
                        if (keyLower.contains("limit") || keyLower.contains("speed")) {
                            contentBuilder.append(" 速限 ").append(value).append(" ")
                        } else if (keyLower.contains("dist")) {
                            contentBuilder.append(" 距離 ").append(value).append("m ")
                        }
                    }
                }
            }
        }

        val ticker = sbn.notification.tickerText?.toString() ?: ""
        if (ticker.isNotEmpty()) {
            contentBuilder.append(ticker).append(" ")
        }

        val rawContent = contentBuilder.toString().trim()
        lastRawText = if (rawContent.isNotEmpty()) rawContent else "(通知內文為空)"

        val parsedData = parseNotificationContent(rawContent)

        // 雙軌時速融合：若 GPS 原生已有車速且大於 0，優先保留高頻 GPS 車速；若 GPS 搜尋中則採用神盾解析時速
        val effectiveSpeed = if (GpsSpeedManager.currentGpsSpeed > 0) {
            GpsSpeedManager.currentGpsSpeed
        } else if (parsedData.currentSpeed > 0) {
            parsedData.currentSpeed
        } else {
            0
        }

        val finalData = parsedData.copy(currentSpeed = effectiveSpeed)
        latestData = finalData

        // 1. 推送更新給 CarWebGuru AppWidget
        CarHudWidgetProvider.updateWidgetData(
            context = this,
            speedLimit = finalData.speedLimit,
            distMeters = finalData.distanceMeters,
            currentSpeed = finalData.currentSpeed,
            camType = finalData.camType
        )

        // 2. 觸發即時回呼
        onDataUpdated?.invoke(finalData)
        onDebugInfoUpdated?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        if (!isSpeedTrapPackage(sbn.packageName)) return

        lastNotificationTimeMillis = System.currentTimeMillis()

        // 檢查神盾是否還有其他作用中的通知
        val hasActiveSpeedTrap = try {
            activeNotifications?.any { isSpeedTrapPackage(it.packageName) } ?: false
        } catch (e: Exception) {
            false
        }

        if (!hasActiveSpeedTrap) {
            // 警報解除時：速限清空為 0 (HUD 顯示 --)，距離重設為 0，絕不鎖死 60
            val clearedData = SpeedTrapData(
                speedLimit = 0,
                distanceMeters = 0,
                currentSpeed = if (GpsSpeedManager.currentGpsSpeed > 0) GpsSpeedManager.currentGpsSpeed else latestData.currentSpeed,
                camType = "🟢 路況順暢"
            )
            latestData = clearedData

            CarHudWidgetProvider.updateWidgetData(
                context = this,
                speedLimit = 0,
                distMeters = 0,
                currentSpeed = clearedData.currentSpeed,
                camType = "🟢 路況順暢"
            )
            onDataUpdated?.invoke(clearedData)
            onDebugInfoUpdated?.invoke()
        }
    }

    private fun parseNotificationContent(content: String): SpeedTrapData {
        var limit = 0
        var dist = 0
        var speed = 0
        var camType = "測速照相"

        try {
            // 1. 匹配相機類型
            when {
                content.contains("科技執法") -> camType = "科技執法"
                content.contains("區間") -> camType = "區間測速"
                content.contains("流動") -> camType = "流動測速"
                content.contains("闖紅燈") -> camType = "闖紅燈照相"
                content.contains("固定") -> camType = "固定測速"
                content.contains("違規取締") -> camType = "違規取締"
                content.contains("未禮讓") -> camType = "未禮讓行人"
                content.contains("車距") -> camType = "未保持車距"
                content.contains("高架") -> camType = "高架測速"
                content.contains("測速") || content.contains("照相") -> camType = "測速照相"
            }

            // 2. 匹配距離 (優先解析距離，避免距離數值干擾速限判定)
            // 2a. 帶小數點之公里距離 (如: 1.2公里、0.8km，小數點必為距離)
            val decimalKmMatcher = Pattern.compile("(\\d+\\.\\d+)\\s*(?:公里|km)", Pattern.CASE_INSENSITIVE).matcher(content)
            if (decimalKmMatcher.find()) {
                val km = decimalKmMatcher.group(1)?.toFloatOrNull() ?: 0f
                dist = (km * 1000).toInt()
            }

            // 2b. 明確帶有「前方/距離/距/剩餘」的前綴公里數 (如: 前方 2 公里、距離 1 公里)
            if (dist == 0) {
                val prefixKmMatcher = Pattern.compile("(?:前方|距離|距|剩餘)\\s*(\\d+)\\s*(?:公里|km)", Pattern.CASE_INSENSITIVE).matcher(content)
                if (prefixKmMatcher.find()) {
                    val km = prefixKmMatcher.group(1)?.toFloatOrNull() ?: 0f
                    dist = (km * 1000).toInt()
                }
            }

            // 2c. 公尺/米/m 距離 (如: 500公尺、350m、400 米、前方 200m)
            if (dist == 0) {
                val mMatcher = Pattern.compile("(\\d+)\\s*(?:公尺|米)", Pattern.CASE_INSENSITIVE).matcher(content)
                if (mMatcher.find()) {
                    dist = mMatcher.group(1)?.toIntOrNull() ?: 0
                }
            }
            if (dist == 0) {
                // 排除 km/h、kph 之後的獨立 m 距離 (例如 350m, 400 m)
                val mStandaloneMatcher = Pattern.compile("(?<!(?:km|kp)/)\\b(\\d{2,4})\\s*m\\b", Pattern.CASE_INSENSITIVE).matcher(content)
                if (mStandaloneMatcher.find()) {
                    dist = mStandaloneMatcher.group(1)?.toIntOrNull() ?: 0
                }
            }

            // 3. 匹配目前車速 (若通知中有提及時速)
            // 支援: "目前時速: 55", "時速 60", "目前車速 70", "車速 80", "目前: 70"
            val speedMatcher = Pattern.compile("(?:目前時速|目前車速|目前速度|車速|速度|目前)\\s*[:：=]?\\s*(\\d{1,3})").matcher(content)
            if (speedMatcher.find()) {
                val s = speedMatcher.group(1)?.toIntOrNull() ?: 0
                if (s in 0..250) {
                    speed = s
                }
            }

            // 4. 匹配速限 (核心算法，完整解析各測速軟體實際格式)
            // 規則 A: 明確速限前綴 (支援: "限速 50", "速限: 60", "速限：70", "限制時速 110", "最高速限 100", "道路速限 60", "速限值 80", "限速50公里", "速限60km/h")
            val explicitLimitMatcher = Pattern.compile("(?:限速|速限|限制時速|速限值|時速限制|最高速限|道路速限|法定速限|速限為|限速為)\\s*[:：=]?\\s*(\\d{2,3})").matcher(content)
            if (explicitLimitMatcher.find()) {
                val l = explicitLimitMatcher.group(1)?.toIntOrNull() ?: 0
                if (l in 20..140) {
                    limit = l
                }
            }

            // 規則 B: 相機關鍵字後方緊接速限 (如: "固定測速照相 50公里", "固定測速 50", "固定照相 (50)", "區間測速 [60]", "科技執法 50公里", "流動照相 40")
            if (limit == 0) {
                val camFollowedByLimitMatcher = Pattern.compile("(?:固定測速|固定照相|固定式|區間測速|區間起點|區間終點|流動測速|流動照相|科技執法|違規取締|測速照相|測速|照相)\\s*[:：=\\s-]*[\\[(（]?\\s*(\\d{2,3})\\s*(?:公里|km/h|km|kph)?[\\])）]?").matcher(content)
                if (camFollowedByLimitMatcher.find()) {
                    val l = camFollowedByLimitMatcher.group(1)?.toIntOrNull() ?: 0
                    if (l in 20..140) {
                        limit = l
                    }
                }
            }

            // 規則 C: 括弧/方括號包圍之速限 (如: "[50] 固定測速照相", "(60) 區間測速", "固定測速 (50)")
            if (limit == 0) {
                val bracketMatcher = Pattern.compile("[\\[(（]\\s*(\\d{2,3})\\s*(?:公里|km/h|kph)?[\\])）]\\s*(?:固定|測速|照相|區間|科技執法|流動)").matcher(content)
                if (bracketMatcher.find()) {
                    val l = bracketMatcher.group(1)?.toIntOrNull() ?: 0
                    if (l in 20..140) {
                        limit = l
                    }
                }
            }

            // 規則 D: "XX公里" 或 "XX km/h" 且排除距離與目前時速後的速限值 (如: "前方 300 公尺 50公里 取締")
            if (limit == 0) {
                val kmUnitMatcher = Pattern.compile("(\\d{2,3})\\s*(?:公里|km/h|kph)", Pattern.CASE_INSENSITIVE).matcher(content)
                while (kmUnitMatcher.find()) {
                    val l = kmUnitMatcher.group(1)?.toIntOrNull() ?: 0
                    if (l in 20..140 && l != dist && l != speed) {
                        limit = l
                        break
                    }
                }
            }

            // 5. 判斷是否為無警報之常駐/待命通知 (若無相機且無距離)
            val isStandbyNotification = (dist == 0 && limit == 0) && (
                content.contains("背景執行") ||
                content.contains("背景常駐") ||
                content.contains("定位完成") ||
                content.contains("GPS 定位中") ||
                content.contains("常駐通知") ||
                content.contains("路況順暢") ||
                content.contains("點擊返回神盾") ||
                content.contains("神盾正在背景")
            )

            if (isStandbyNotification) {
                camType = "🟢 神盾監控中"
                limit = 0
            } else if (dist == 0 && limit == 0 && (content.contains("神盾") || content.contains("常駐") || content.contains("背景"))) {
                camType = "🟢 神盾監控中"
                limit = 0
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 動態速限解析邏輯：
        // 1. 若本次通知成功解析出速限 (limit > 0)，立即採用該速限數值。
        // 2. 若相機警報倒數中 (dist > 0) 且既有快取已有速限，在倒數過程中延續該相機速限。
        // 3. 待命、無相機、或警報解除時速限為 0 (HUD 顯示 --)，絕不鎖定為 60！
        val effectiveLimit = when {
            limit > 0 -> limit
            dist > 0 && latestData.distanceMeters > 0 && latestData.speedLimit > 0 -> latestData.speedLimit
            else -> 0
        }

        return SpeedTrapData(
            speedLimit = effectiveLimit,
            distanceMeters = dist,
            currentSpeed = speed,
            camType = camType,
            rawText = content
        )
    }
}
