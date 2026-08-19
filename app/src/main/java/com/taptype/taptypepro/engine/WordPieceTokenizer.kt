package com.taptype.taptypepro.engine

import java.io.File

/**
 * Minimal, faithful bert-base-uncased WordPiece tokenizer.
 *
 * Loads a vocab.txt (one token per line, id = line index) and produces the input
 * tensors for a BERT token-classification model. Special-token ids are resolved
 * dynamically from the vocab (never hardcoded) because some vocab files shift the
 * [unusedN] block relative to [UNK]/[CLS]/[SEP].
 */
class WordPieceTokenizer(vocabFile: File) {
    private val vocab = HashMap<String, Int>()
    private val idToToken = HashMap<Int, String>()
    private val clsId: Int
    private val sepId: Int
    private val unkId: Int
    private val maskId: Int

    init {
        vocabFile.readLines().forEachIndexed { i, line ->
            if (line.isNotEmpty()) {
                vocab[line] = i
                idToToken[i] = line
            }
        }
        clsId = vocab["[CLS]"] ?: 101
        sepId = vocab["[SEP]"] ?: 102
        unkId = vocab["[UNK]"] ?: 100
        maskId = vocab["[MASK]"] ?: 103
    }

    /** Token string for a token id (reverse vocab lookup), or null. */
    fun tokenForId(id: Int): String? = idToToken[id]

    data class Tokenized(
        val ids: LongArray,
        val mask: LongArray,
        val typeIds: LongArray,
        /** Token position of each original word's first wordpiece (parallel to words). */
        val wordStarts: IntArray,
        /** Flat token index of each [MASK] token (for masked-LM fill). */
        val maskPositions: IntArray = IntArray(0)
    ) {
        val wordCount: Int get() = wordStarts.size
    }

    /**
     * Tokenize [text] into BERT input tensors. [maxLen] is the maximum sequence
     * length including [CLS] (a slot is reserved for [SEP]). Words that do not fit
     * are dropped (wordStarts only covers encoded words).
     */
    fun tokenize(text: String, maxLen: Int = 254): Tokenized {
        val ids = ArrayList<Int>()
        ids.add(clsId)
        val wordStarts = ArrayList<Int>()

        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            // Basic tokenization (lowercase + split on punctuation), then WordPiece.
            val pieces = ArrayList<String>()
            for (bt in basicTokenize(word)) pieces.addAll(wordPiece(bt))
            if (pieces.isEmpty()) continue
            if (ids.size + pieces.size >= maxLen) break  // reserve room for [SEP]
            val first = ids.size
            for (wp in pieces) ids.add(vocab[wp] ?: unkId)
            wordStarts.add(first)
        }
        ids.add(sepId)

        val n = ids.size
        return Tokenized(
            ids = LongArray(n) { ids[it].toLong() },
            mask = LongArray(n) { 1L },
            typeIds = LongArray(n) { 0L },
            wordStarts = wordStarts.toIntArray()
        )
    }

    /**
     * Tokenize [text], replacing the word at [maskWordIndex] with a single [MASK]
     * token. Same tensor layout as [tokenize], plus [Tokenized.maskPositions] holds
     * the flat token index of the mask so the caller knows where to read logits.
     */
    fun tokenizeMasked(text: String, maskWordIndex: Int, maxLen: Int = 254): Tokenized {
        val ids = ArrayList<Int>()
        ids.add(clsId)
        val wordStarts = ArrayList<Int>()
        val maskPositions = ArrayList<Int>()

        var wi = 0
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val first = ids.size
            if (wi == maskWordIndex) {
                ids.add(maskId)
                maskPositions.add(first)
                wordStarts.add(first)
            } else {
                val pieces = ArrayList<String>()
                for (bt in basicTokenize(word)) pieces.addAll(wordPiece(bt))
                if (pieces.isEmpty()) continue
                if (ids.size + pieces.size >= maxLen) break
                for (wp in pieces) ids.add(vocab[wp] ?: unkId)
                wordStarts.add(first)
            }
            wi++
        }
        ids.add(sepId)

        val n = ids.size
        return Tokenized(
            ids = LongArray(n) { ids[it].toLong() },
            mask = LongArray(n) { 1L },
            typeIds = LongArray(n) { 0L },
            wordStarts = wordStarts.toIntArray(),
            maskPositions = maskPositions.toIntArray()
        )
    }

    /** Lowercase and split on whitespace + punctuation (BERT basic tokenizer). */
    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in lower) {
            if (c in PUNCT) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                out.add(c.toString())
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Greedy longest-match-first WordPiece. First piece unprefixed, rest prefixed with "##". */
    private fun wordPiece(token: String): List<String> {
        if (token in vocab) return listOf(token)
        val out = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            var end = token.length
            var found: String? = null
            while (end > i) {
                val prefix = if (i > 0) "##" + token.substring(i, end) else token.substring(i, end)
                if (prefix in vocab) { found = prefix; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            out.add(found)
            i = end
        }
        return out
    }

    private companion object {
        val PUNCT = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toSet()
    }
}
