package com.phynex.NexLink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Receives USB device attach/detach system broadcasts and delegates to UsbControlManager.
 */
class UsbBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "UsbBroadcastReceiver"


    override fun onReceive(context: Context, intent: Intent) {
        val manager = UsbControlManager(context)
        val device  = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d(TAG, "USB attached: ${device?.deviceName}")
                manager.onUsbDeviceAttached(device)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.d(TAG, "USB detached: ${device?.deviceName}")
                manager.onUsbDeviceDetached(device)
            }
        }
    }
}
