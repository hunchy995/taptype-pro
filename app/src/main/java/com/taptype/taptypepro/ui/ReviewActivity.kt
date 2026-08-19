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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.taptype.taptypepro.R
import com.taptype.taptypepro.service.TapTypeAccessibilityService
import com.taptype.taptypepro.util.DebugLog

/**
 * A lightweight "review before paste" card. Shown after dictation when the engine
 * flagged low-confidence words: the text is pre-filled with those words underlined
 * in red, the user edits it, then Pastes (or Cancels). Tapping a low-confidence word
 * will offer contextual suggestions once the MLM filler is wired in.
 */
class ReviewActivity : Activity() {
    companion object {
        private const val TAG = "ReviewActivity"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_LOW_WORDS = "extra_low_words"
    }

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val lowWords = intent.getStringArrayListExtra(EXTRA_LOW_WORDS)?.toSet() ?: emptySet()

        setContentView(buildLayout(text, lowWords))
        DebugLog.i(TAG, "Review card shown: ${lowWords.size} low-confidence words")
    }

    private fun buildLayout(initialText: String, lowWords: Set<String>): ViewGroup {
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

        val title = TextView(this).apply {
            setTextColor(textPrimary)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            text = "Review before pasting"
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
            text = if (lowWords.isEmpty())
                "Edit if needed, then paste."
            else
                "Red underlined words may be wrong — tap and edit them, then paste."
        }
        root.addView(subtitle)

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
            setText(buildSpannable(initialText, lowWords, red))
            setSelection(initialText.length)
        }
        scroll.addView(
            editText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, dp(8), 0, dp(12)) }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
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
            background = GradientDrawable().apply {
                setColor(clay)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { onPaste() }
        }

        buttons.addView(
            cancel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(8), 0) }
        )
        buttons.addView(
            paste,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(buttons)

        return root
    }

    private fun buildSpannable(text: String, lowWords: Set<String>, red: Int): SpannableString {
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

    private fun onPaste() {
        val edited = editText.text.toString().trim()
        DebugLog.i(TAG, "Review confirmed: \"$edited\"")
        TapTypeAccessibilityService.reviewResult = edited
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
