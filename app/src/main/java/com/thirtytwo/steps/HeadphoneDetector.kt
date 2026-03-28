package com.thirtytwo.steps

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Detects headphone connect/disconnect events and triggers profile auto-switching.
 * Uses AudioDeviceCallback for reliable detection of Bluetooth, wired, and USB audio devices.
 */
class HeadphoneDetector(
    private val context: Context,
    private val onDeviceConnected: (deviceKey: String, deviceName: String) -> Unit,
    private val onDeviceDisconnected: (deviceKey: String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    // Debounce: Bluetooth devices fire multiple events (A2DP + HFP)
    private var pendingConnect: Runnable? = null
    private var lastConnectedKey: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (!device.isSink) continue // output devices only
                if (!isHeadphoneType(device.type)) continue

                val key = deviceKey(device)
                val name = deviceName(device)

                // Debounce: wait 500ms to collapse multiple events from same device
                pendingConnect?.let { handler.removeCallbacks(it) }
                pendingConnect = Runnable {
                    lastConnectedKey = key
                    onDeviceConnected(key, name)
                }
                handler.postDelayed(pendingConnect!!, 500)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (!device.isSink) continue
                if (!isHeadphoneType(device.type)) continue

                val key = deviceKey(device)
                if (key == lastConnectedKey) {
                    pendingConnect?.let { handler.removeCallbacks(it) }
                    lastConnectedKey = null
                    onDeviceDisconnected(key)
                }
            }
        }
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun unregister() {
        pendingConnect?.let { handler.removeCallbacks(it) }
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    /** Check currently connected devices and trigger callback if a mapping exists */
    fun checkCurrentDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (!isHeadphoneType(device.type)) continue
            val key = deviceKey(device)
            val name = deviceName(device)
            lastConnectedKey = key
            onDeviceConnected(key, name)
            return // apply first match only
        }
    }

    companion object {
        fun isHeadphoneType(type: Int): Boolean = type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        fun deviceKey(device: AudioDeviceInfo): String {
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown"
            val type = when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
                else -> "wired"
            }
            return "$name|$type"
        }

        fun deviceName(device: AudioDeviceInfo): String {
            return device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown device"
        }
    }
}
