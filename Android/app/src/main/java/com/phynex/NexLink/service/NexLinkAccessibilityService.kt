package com.phynex.NexLink.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.phynex.NexLink.viewmodel.MainViewModel

class NexLinkAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var lastClipHash: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // Listen to clipboard changes if allowed
        clipboardManager?.addPrimaryClipChangedListener {
            checkClipboard()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // As a fallback, we can monitor text selection/copy events
        // Accessibility nodes can be queried for text if they are being copied,
        // but for now we just rely on the global service context to check clipboard.
        
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // Periodically poll when user interacts
            checkClipboard()
        }
    }

    private fun checkClipboard() {
        val vm = MainViewModel.instance ?: return
        if (!vm.isConnected.value) return
        try {
            val item = clipboardManager?.primaryClip?.getItemAt(0)
            val text = item?.text?.toString()
            if (!text.isNullOrEmpty()) {
                val hash = text.hashCode()
                if (hash != lastClipHash) {
                    lastClipHash = hash
                    vm.pushClipboard(text)
                }
            }
        } catch (_: Exception) {
            // Exception may happen if background clipboard read is fully blocked by OS
        }
    }

    override fun onInterrupt() {
        // Required override
    }
}
