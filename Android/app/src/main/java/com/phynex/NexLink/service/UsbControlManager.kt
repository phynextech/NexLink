package com.phynex.NexLink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import com.phynex.NexLink.viewmodel.MainViewModel

/**
 * UsbControlManager — detects USB connection and enables touchpad mode.
 *
 * When a USB device is connected (cable to laptop in host mode), this service:
 *   1. Notifies the server via `usb_connected` event
 *   2. Begins listening for touch gestures on the mobile screen
 *   3. Translates gestures into mouse events sent to the relay server:
 *        - Move finger   → mouse_move   { dx, dy }
 *        - Single tap    → mouse_tap    { x, y }
 *        - Two-finger    → mouse_right_tap { x, y }
 *        - Scroll        → mouse_scroll  { dy }
 *   4. On USB disconnect → sends `usb_disconnected`, reverts to WebSocket mode
 *
 * The Windows laptop receives these events and executes them via SendInput.
 */
class UsbControlManager(private val context: Context) {

    private val TAG = "UsbControlManager"
    private var isUsbMode = false

    companion object {
        /** Broadcast action sent by the system when USB device is attached */
        const val ACTION_USB_ATTACHED   = UsbManager.ACTION_USB_DEVICE_ATTACHED
        const val ACTION_USB_DETACHED   = UsbManager.ACTION_USB_DEVICE_DETACHED
        const val ACTION_USB_PERMISSION = "com.phynex.NexLink.USB_PERMISSION"
    }

    /** Call this when a USB device attachment broadcast is received */
    fun onUsbDeviceAttached(device: UsbDevice?) {
        Log.d(TAG, "USB device attached: ${device?.deviceName}")
        isUsbMode = true
        MainViewModel.instance?.onUsbConnected()
    }

    /** Call this when a USB device detachment broadcast is received */
    fun onUsbDeviceDetached(device: UsbDevice?) {
        Log.d(TAG, "USB device detached: ${device?.deviceName}")
        if (isUsbMode) {
            isUsbMode = false
            MainViewModel.instance?.onUsbDisconnected()
        }
    }

    fun isUsbModeActive(): Boolean = isUsbMode

    /**
     * Create a GestureDetector that translates screen touches into relay events.
     * Attach this to a full-screen composable's `onTouchEvent` / `pointerInteropFilter`.
     */
    fun createGestureDetector(): GestureDetector {
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val vm = MainViewModel.instance ?: return false
                // Two-pointer scroll = scroll wheel
                if ((e2.pointerCount) >= 2) {
                    vm.sendMouseScroll(distanceY)
                } else {
                    vm.sendMouseMove(-distanceX, -distanceY)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                MainViewModel.instance?.sendMouseTap() ?: return false
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                MainViewModel.instance?.sendMouseRightTap() ?: return false
                return true
            }
        })
    }
}
