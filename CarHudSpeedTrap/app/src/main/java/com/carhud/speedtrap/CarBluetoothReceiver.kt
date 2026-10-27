package com.carhud.speedtrap

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class CarBluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBluetoothReceiver"
        const val PREF_NAME = "carhud_prefs"
        const val KEY_AUTO_BT_ENABLED = "auto_bt_enabled"
        const val KEY_AUTO_LAUNCH_AA = "auto_launch_aa"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_AUTO_BT_ENABLED, true)
        val autoLaunchAa = prefs.getBoolean(KEY_AUTO_LAUNCH_AA, true)

        if (!isEnabled) {
            Log.d(TAG, "藍牙自動化已停用，略過事件")
            return
        }

        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try {
            device?.name ?: device?.address ?: "手機裝置"
        } catch (e: SecurityException) {
            "手機裝置"
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(TAG, "已連線至藍牙裝置: $deviceName，執行上車自動化排程...")
                Toast.makeText(context, "🚗 藍牙已連線 ($deviceName)，啟動車載自動化...", Toast.LENGTH_SHORT).show()

                // 1. 自動開啟 Wi-Fi (以利連線手機熱點)
                QuickSwitchHelper.toggleWifi(context)

                // 2. 自動在背景喚醒神盾測速
                SpeedTrapAutoLauncher.startSpeedTrapIfNeeded(context, bringHomeToFront = true)

                // 3. 延遲 3 秒啟動投屏 / Android Auto (待熱點連線就緒)
                if (autoLaunchAa) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "自動啟動投屏 App...")
                        QuickSwitchHelper.launchAndroidAuto(context)
                    }, 3000)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "藍牙裝置已斷開: $deviceName (下車/熄火)")
            }
        }
    }
}
