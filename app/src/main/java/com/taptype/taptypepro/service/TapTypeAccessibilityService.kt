package com.taptype.taptypepro.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.taptype.taptypepro.util.DebugLog
import com.taptype.taptypepro.util.Settings
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

    fun onTranscriptionComplete() {
        orb?.onTranscriptionComplete()
    }

    fun onOrbSizeChanged(dp: Int) {
        orb?.onOrbSizeChanged(dp)
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
        // Final safety net: strip any leading filler word (user-configured plus
        // the built-in "Message" hallucination) right before pasting, and trim
        // leading whitespace.
        val clean = stripLeadingWords(text)
        if (clean.isEmpty()) return

        // Get a FRESH node so we read the field's current text, not a stale snapshot.
        val node = getFreshInputNode() ?: return
        try {
            val currentText = node.text?.toString() ?: ""
            val hintText = node.hintText?.toString() ?: ""
            DebugLog.d(TAG, "injectText: current='$currentText', hint='$hintText', new='$clean'")
            // A field's placeholder/hint (e.g. Telegram's "Message") is NOT real
            // content — if "current" text is actually just the hint, treat the
            // field as empty so we don't prepend the placeholder to the result.
            val realCurrent = if (currentText.isNotBlank() && currentText == hintText) "" else currentText
            val combined = if (realCurrent.isBlank()) clean else "$realCurrent $clean"
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                DebugLog.i(TAG, "Text injection succeeded (SET_TEXT)")
                return
            }
            fallbackClipboardPaste(clean, node)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Text injection failed", e)
        } finally {
            node.recycle()
        }
    }

    // Removes a leading word (case-insensitive, standalone) if it matches the
    // user-configured filter list or the built-in "message"/"message inaudible"
    // hallucination. Trims leading whitespace afterwards.
    private fun stripLeadingWords(text: String): String {
        var s = text.trimStart()

        val builtIn = listOf("message inaudible", "message")
        val userWords = Settings.filterWords()

        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (word in builtIn + userWords) {
                if (word.isBlank()) continue
                // Match the word at the very start, followed by a word boundary,
                // then swallow any trailing whitespace/punctuation.
                val regex = Regex(
                    "^" + Regex.escape(word) + "\\b[\\s.,!?;:]*",
                    RegexOption.IGNORE_CASE
                )
                val after = regex.replaceFirst(s, "")
                if (after != s) {
                    s = after.trimStart()
                    changed = true
                    break
                }
            }
        }
        return s
    }

    private fun getFreshInputNode(): AccessibilityNodeInfo? {
        // Pass 1: refresh the cached node if it's still alive and editable
        currentFocusedNode?.let { cached ->
            if (cached.refresh() && (cached.isEditable || isTextClass(cached.className))) {
                DebugLog.d(TAG, "getFreshInputNode: refreshed cached node")
                return AccessibilityNodeInfo.obtain(cached)
            }
            cached.recycle()
            currentFocusedNode = null
        }

        // Pass 2: find focus in the active window
        rootInActiveWindow?.let { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.recycle()
            if (focused != null && (focused.isEditable || isTextClass(focused.className))) {
                DebugLog.d(TAG, "getFreshInputNode: found in active window")
                return focused
            }
            focused?.recycle()
        }

        // Pass 3: scan all windows for an editable field
        for (window in windows) {
            val root = window.root ?: continue
            val editable = findEditable(root)
            root.recycle()
            if (editable != null) {
                DebugLog.d(TAG, "getFreshInputNode: found in windows scan")
                return editable
            }
        }
        DebugLog.w(TAG, "getFreshInputNode: no editable field found")
        return null
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable || isTextClass(root.className)) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
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
