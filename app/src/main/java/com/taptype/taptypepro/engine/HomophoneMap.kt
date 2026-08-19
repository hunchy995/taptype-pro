package com.taptype.taptypepro.engine

/**
 * Word-level corrections applied to raw ASR output BEFORE punctuation restoration.
 *
 * CTC models (Parakeet) mishear short/homophone words and drop apostrophes. The
 * punctuation model cannot fix a wrong word, so these run first. All replacements
 * are whole-word and case-insensitive; the canonical output is lowercase, and the
 * punctuation model (or the "i" → "I" pass) restores capitalization afterward.
 */
object HomophoneMap {
    private val replacements = linkedMapOf(
        // homophones of the pronoun "I" (CTC confuses these constantly)
        "eye" to "i",
        "aye" to "i",
        // missing apostrophes — these forms aren't valid English words, so it's safe
        "im" to "i'm",
        "ive" to "i've",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "havent" to "haven't",
        "hasnt" to "hasn't",
        "hadnt" to "hadn't",
        "shouldnt" to "shouldn't",
        "couldnt" to "couldn't",
        "wouldnt" to "wouldn't",
    )

    fun apply(text: String): String {
        var out = text
        for ((from, to) in replacements) {
            out = out.replace(Regex("\\b" + Regex.escape(from) + "\\b", RegexOption.IGNORE_CASE), to)
        }
        return out
    }
}
