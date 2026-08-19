package com.taptype.taptypepro.engine

/**
 * Converts written-out number words in ASR output into digits.
 *
 * ASR engines (Whisper and especially CTC models like Parakeet) emit numbers as
 * words: "twenty twenty six" instead of "2026", "forty two" instead of "42",
 * "one two three" instead of "123". This pass finds runs of number words and
 * rewrites them as digits, including year-style phrasing ("twenty twenty six"
 * -> 2026, "nineteen ninety nine" -> 1999), scale phrasing ("two thousand
 * twenty six" -> 2026), decimals ("three point five" -> 3.5) and grouped
 * digits ("one two three" -> 123).
 */
object NumberNormalizer {

    private val ones = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val teens = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val scales = setOf("hundred", "thousand", "million", "billion")

    private fun isDecade(w: String) = w in teens || w in tens

    /** A word that can be part of a number phrase (punctuation already stripped, any case). */
    private fun isNumWord(w: String) =
        w in ones || w in teens || w in tens || w in scales || w == "point"

    /**
     * True if the given word is a number word (case-insensitive, punctuation
     * stripped internally). Used by the duplicate-word dedupe so it never
     * collapses a legitimate "twenty twenty" year sequence into "twenty".
     */
    fun isNumberWord(word: String): Boolean {
        val w = word.lowercase().replace(Regex("[^\\w]"), "")
        return isNumWord(w) || w == "a"
    }

    /** Parse a small (< 100) number from tens/teens/ones tokens, e.g. "twenty six" -> 26. */
    private fun word2numberSmall(tokens: List<String>): Int? {
        var v = 0
        for (t in tokens) {
            v += teens[t] ?: tens[t] ?: ones[t] ?: return null
        }
        return v
    }

    /** Standard number-word parse with scale words (hundred/thousand/million/billion). */
    private fun word2number(tokens: List<String>): Long {
        var total = 0L
        var current = 0L
        for (raw in tokens) {
            val t = if (raw == "a") "one" else raw
            when {
                t in ones -> current += ones[t]!!
                t in teens -> current += teens[t]!!
                t in tens -> current += tens[t]!!
                t == "hundred" -> current = (if (current == 0L) 1L else current) * 100
                t == "thousand" -> { total += (if (current == 0L) 1L else current) * 1000; current = 0L }
                t == "million" -> { total += (if (current == 0L) 1L else current) * 1000000; current = 0L }
                t == "billion" -> { total += (if (current == 0L) 1L else current) * 1000000000; current = 0L }
            }
        }
        return total + current
    }

    /** Convert a run of number tokens to a digit string, or null if not a valid number. */
    private fun parsePhrase(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null

        // Decimal: "three point five" -> "3.5"
        val pi = tokens.indexOf("point")
        if (pi >= 0) {
            val left = tokens.subList(0, pi)
            val right = tokens.subList(pi + 1, tokens.size)
            if (right.isEmpty()) return null
            val intPart = if (left.isEmpty()) 0L else word2number(left)
            val sb = StringBuilder()
            for (t in right) {
                val d = ones[t] ?: teens[t] ?: tens[t] ?: return null
                sb.append(d)
            }
            return "$intPart.$sb"
        }

        // Year-style: first two words are decade-words -> "twenty twenty six" = 2026,
        // "nineteen ninety nine" = 1999, "nineteen eighty four" = 1984.
        if (tokens.size >= 2 && isDecade(tokens[0]) && isDecade(tokens[1])) {
            val century = teens[tokens[0]] ?: tens[tokens[0]] ?: 0
            val rest = word2numberSmall(tokens.subList(1, tokens.size))
            if (rest != null && rest in 0..99) {
                return (century * 100 + rest).toString()
            }
        }

        // Grouped digits: "one two three" -> "123" (codes, phone numbers, PINs).
        if (tokens.size >= 2 && tokens.all { it in ones }) {
            return tokens.joinToString("") { ones[it].toString() }
        }

        return word2number(tokens).toString()
    }

    /** Rewrite all number-word runs in [text] as digits. */
    fun normalize(text: String): String {
        // Treat hyphens between word chars as spaces so "twenty-six" / "twenty-twenty-six"
        // tokenize as separate number words (Whisper hyphenates compound numbers).
        val pre = Regex("(?<=\\w)-(?=\\w)").replace(text, " ")
        val words = pre.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        val n = words.size
        while (i < n) {
            val core = words[i].lowercase().replace(Regex("[^\\w]"), "")
            val nextCore = if (i + 1 < n) words[i + 1].lowercase().replace(Regex("[^\\w]"), "") else ""
            if (isNumWord(core) || (core == "a" && nextCore in scales)) {
                val run = ArrayList<String>()
                var j = i
                while (j < n) {
                    val cw = words[j].lowercase().replace(Regex("[^\\w]"), "")
                    when {
                        isNumWord(cw) || cw == "a" -> { run.add(cw); j++ }
                        cw == "and" -> {
                            val nxt = if (j + 1 < n) words[j + 1].lowercase().replace(Regex("[^\\w]"), "") else ""
                            if (nxt.isNotEmpty() && (isNumWord(nxt) || nxt == "a")) j++ else break
                        }
                        else -> break
                    }
                }
                val res = parsePhrase(run)
                if (res != null) {
                    out.add(res)
                } else {
                    for (k in i until j) out.add(words[k])
                }
                i = j
            } else {
                out.add(words[i])
                i++
            }
        }
        return out.joinToString(" ")
    }
}
