package com.taptype.taptypepro.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.taptype.taptypepro.R
import com.taptype.taptypepro.engine.MaskedLMFiller
import com.taptype.taptypepro.service.TapTypeAccessibilityService
import com.taptype.taptypepro.util.DebugLog

/**
 * A lightweight "review before paste" card. Shown after dictation when the engine
 * flagged low-confidence words: those words are underlined in red. Tap one to see
 * contextual suggestions (on-device fill-mask model), or hit "Fix all" to auto-replace
 * every uncertain word with the best suggestion. Paste confirms; Cancel discards.
 */
class ReviewActivity : Activity() {
    companion object {
        private const val TAG = "ReviewActivity"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_LOW_WORDS = "extra_low_words"
        private const val SUGGEST_K = 5
    }

    private lateinit var editText: EditText
    private lateinit var suggestionStrip: LinearLayout
    private lateinit var fixAllButton: Button
    private var lowWords: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        lowWords = intent.getStringArrayListExtra(EXTRA_LOW_WORDS)?.toSet() ?: emptySet()
        setContentView(buildLayout(text))
        DebugLog.i(TAG, "Review card shown: ${lowWords.size} low-confidence words")
    }

    private fun buildLayout(initialText: String): ViewGroup {
        val cream = getColor(R.color.cream)
        val white = getColor(R.color.white)
        val textPrimary = getColor(R.color.text_primary)
        val textSecondary = getColor(R.color.text_secondary)
        val clay = getColor(R.color.clay)
        val red = getColor(R.color.recording_red)
        val border = getColor(R.color.border)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cream)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            setTextColor(textPrimary)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            text = "Review before pasting"
        })

        root.addView(TextView(this).apply {
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
            text = "Tap a red word for suggestions, edit freely, then paste."
        })

        val scroll = ScrollView(this)
        editText = EditText(this).apply {
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(white)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), border)
            }
            setText(buildSpannable(initialText, red))
            setSelection(initialText.length)
            setOnTouchListener { _, event -> onTextTouched(event) }
        }
        scroll.addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        // Suggestion strip (autocorrect-style chips), hidden until a word is tapped.
        suggestionStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = android.view.View.GONE
        }
        root.addView(
            HorizontalScrollView(this).apply {
                addView(suggestionStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                isHorizontalScrollBarEnabled = false
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        fixAllButton = Button(this).apply {
            text = "Fix all"
            setTextColor(textPrimary)
            background = GradientDrawable().apply {
                setColor(white)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), border)
            }
            setOnClickListener { fixAll() }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setTextColor(textPrimary)
            background = GradientDrawable().apply {
                setColor(white)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), border)
            }
            setOnClickListener { finish() }
        }
        val paste = Button(this).apply {
            text = "Paste"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(clay); cornerRadius = dp(12).toFloat() }
            setOnClickListener { onPaste() }
        }
        buttons.addView(fixAllButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(8), 0) })
        buttons.addView(paste, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        return root
    }

    private fun buildSpannable(text: String, red: Int): SpannableString {
        val spannable = SpannableString(text)
        for (word in lowWords) {
            if (word.isBlank()) continue
            val regex = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE)
            for (m in regex.findAll(text)) {
                val start = m.range.first
                val end = m.range.last + 1
                spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(red), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    // ---- Tap-to-suggest ----

    private fun onTextTouched(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return false
        val off = editText.getOffsetForPosition(event.x, event.y)
        val text = editText.text.toString()
        val bounds = wordBoundsAt(text, off) ?: return false
        val word = text.substring(bounds.first, bounds.second)
        if (!isLowWord(word)) return false
        val wordIndex = wordIndexForChar(text, bounds.first)
        showSuggestions(wordIndex)
        return false  // let the EditText also place the cursor
    }

    private fun showSuggestions(wordIndex: Int) {
        Thread {
            if (!ensureMlmReady()) {
                runOnUiThread { toast("Suggestion model not ready yet") }
                return@Thread
            }
            val text = editText.text.toString()
            val suggestions = MaskedLMFiller.suggest(text, wordIndex, SUGGEST_K)
            runOnUiThread { renderSuggestions(wordIndex, suggestions) }
        }.start()
    }

    private fun renderSuggestions(wordIndex: Int, suggestions: List<String>) {
        suggestionStrip.removeAllViews()
        if (suggestions.isEmpty()) {
            suggestionStrip.visibility = android.view.View.GONE
            return
        }
        val textPrimary = getColor(R.color.text_primary)
        val border = getColor(R.color.border)
        for (s in suggestions) {
            suggestionStrip.addView(TextView(this).apply {
                text = s
                setTextColor(textPrimary)
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.white))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), border)
                }
                setOnClickListener { applySuggestion(wordIndex, s) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        suggestionStrip.visibility = android.view.View.VISIBLE
    }

    private fun applySuggestion(wordIndex: Int, replacement: String) {
        val text = editText.text.toString()
        val range = charRangeForWordIndex(text, wordIndex) ?: return
        editText.text.replace(range.first, range.second, replacement)
        editText.setSelection(range.first + replacement.length)
        suggestionStrip.removeAllViews()
        suggestionStrip.visibility = android.view.View.GONE
        DebugLog.i(TAG, "Applied suggestion #$wordIndex -> \"$replacement\"")
    }

    // ---- Fix all ----

    private fun fixAll() {
        fixAllButton.isEnabled = false
        Thread {
            try {
                if (!ensureMlmReady()) {
                    runOnUiThread { toast("Suggestion model not ready yet"); fixAllButton.isEnabled = true }
                    return@Thread
                }
                val text = editText.text.toString()
                val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val lowIdxs = words.indices.filter { isLowWord(words[it]) }
                val newWords = words.toMutableList()
                var changed = 0
                for (i in lowIdxs) {
                    val best = MaskedLMFiller.suggestBest(text, i)
                    if (best != null && best.isNotBlank() && !best.equals(words[i], ignoreCase = true)) {
                        newWords[i] = best
                        changed++
                    }
                }
                runOnUiThread {
                    if (changed > 0) {
                        editText.setText(newWords.joinToString(" "))
                        editText.setSelection(editText.text.length)
                    }
                    fixAllButton.isEnabled = true
                    toast(if (changed > 0) "Fixed $changed words" else "No suggestions applied")
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "fixAll failed", e)
                runOnUiThread { fixAllButton.isEnabled = true }
            }
        }.start()
    }

    // ---- Helpers ----

    private fun ensureMlmReady(): Boolean =
        MaskedLMFiller.isReady || MaskedLMFiller.ensureLoaded(this)

    private fun isLowWord(word: String): Boolean {
        val clean = word.lowercase().trim { it.isWhitespace() || it in ".,!?;:\"" }
        return clean.isNotEmpty() && clean in lowWords
    }

    private fun wordBoundsAt(text: String, offset: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val c = offset.coerceIn(0, text.length)
        var s = c
        var e = c
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < text.length && !text[e].isWhitespace()) e++
        if (s == e) return null
        return s to e
    }

    private fun wordIndexForChar(text: String, charOffset: Int): Int =
        text.substring(0, charOffset).split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    private fun charRangeForWordIndex(text: String, wordIndex: Int): Pair<Int, Int>? {
        var idx = 0
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            var j = i
            while (j < text.length && !text[j].isWhitespace()) j++
            if (idx == wordIndex) return i to j
            idx++
            i = j
        }
        return null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun onPaste() {
        val edited = editText.text.toString().trim()
        DebugLog.i(TAG, "Review confirmed: \"$edited\"")
        TapTypeAccessibilityService.reviewResult = edited
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
