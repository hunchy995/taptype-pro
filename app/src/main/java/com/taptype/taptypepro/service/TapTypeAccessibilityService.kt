package com.taptype.taptypepro.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.view.FloatingOrb

class TapTypeAccessibilityService : android.accessibilityservice.AccessibilityService() {
    companion object {
        private const val TAG = "TapTypeAS"
        var instance: TapTypeAccessibilityService? = null
            private set
    }

    private var orb: FloatingOrb? = null
    private var currentFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DebugLog.i(TAG, "Accessibility service connected")
        ensureOrb()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> updateOverlayState()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        orb?.destroy()
        orb = null
        instance = null
    }

    private fun ensureOrb() {
        if (orb == null) orb = FloatingOrb(this)
    }

    private fun updateOverlayState() {
        ensureOrb()
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            orb?.hide()
            return
        }
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focusedNode != null && (focusedNode.isEditable || isTextClass(focusedNode.className))

        if (editable) {
            currentFocusedNode?.recycle()
            currentFocusedNode = focusedNode
            orb?.show()
        } else {
            focusedNode?.recycle()
            currentFocusedNode?.recycle()
            currentFocusedNode = null
            orb?.hide()
        }
        rootNode.recycle()
    }

    private fun isTextClass(className: CharSequence?): Boolean {
        if (className == null) return false
        val name = className.toString()
        return name.contains("EditText", ignoreCase = true) ||
                name.contains("AutoCompleteTextView", ignoreCase = true) ||
                name.contains("TextInput", ignoreCase = true) ||
                name.contains("Compose", ignoreCase = true)
    }

    fun injectText(text: String) {
        val node = currentFocusedNode ?: return
        try {
            val currentText = node.text?.toString() ?: ""
            val combined = if (currentText.isBlank()) text else "$currentText $text"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                DebugLog.i(TAG, "Direct text injection succeeded")
                return
            }
            fallbackClipboardPaste(text, node)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Text injection failed", e)
        }
    }

    private fun fallbackClipboardPaste(text: String, node: AccessibilityNodeInfo) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TapType Pro", text))
            val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            DebugLog.i(TAG, "Clipboard paste fallback: $success")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Clipboard fallback failed", e)
        }
    }
}
