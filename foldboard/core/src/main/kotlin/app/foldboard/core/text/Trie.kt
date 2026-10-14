package app.foldboard.core.text

/**
 * Compact growable trie stored as parallel primitive arrays (first-child / next-sibling).
 * ~18 bytes per node, so a 60k-word dictionary costs a few MB instead of tens.
 *
 * Paths are lowercase; each terminal node points to a word id whose display form
 * (e.g. "I'm") and frequency live in [words] / [freqs].
 */
class Trie {
    private var size = 1 // node 0 is the root
    private var chars = CharArray(1024)
    private var firstChild = IntArray(1024) { -1 }
    private var nextSibling = IntArray(1024) { -1 }
    private var wordId = IntArray(1024) { -1 }
    /** Highest frequency of any word in the subtree; used to prune completion search. */
    private var maxFreq = FloatArray(1024) { -1f }

    val words = ArrayList<String>()
    val freqs = ArrayList<Float>()

    val nodeCount get() = size
    val wordCount get() = words.size

    private fun grow() {
        val n = chars.size * 2
        chars = chars.copyOf(n)
        firstChild = firstChild.copyOf(n).also { it.fill(-1, size, n) }
        nextSibling = nextSibling.copyOf(n).also { it.fill(-1, size, n) }
        wordId = wordId.copyOf(n).also { it.fill(-1, size, n) }
        maxFreq = maxFreq.copyOf(n).also { it.fill(-1f, size, n) }
    }

    private fun child(node: Int, c: Char): Int {
        var n = firstChild[node]
        while (n != -1) {
            if (chars[n] == c) return n
            n = nextSibling[n]
        }
        return -1
    }

    /** Insert or update a word. Returns the word id. A higher frequency replaces a lower one. */
    fun put(display: String, freq: Float): Int {
        val key = display.lowercase()
        var node = 0
        if (maxFreq[0] < freq) maxFreq[0] = freq
        for (c in key) {
            var nx = child(node, c)
            if (nx == -1) {
                if (size == chars.size) grow()
                nx = size++
                chars[nx] = c
                nextSibling[nx] = firstChild[node]
                firstChild[node] = nx
            }
            if (maxFreq[nx] < freq) maxFreq[nx] = freq
            node = nx
        }
        val existing = wordId[node]
        return if (existing >= 0) {
            if (freq > freqs[existing]) { freqs[existing] = freq; words[existing] = display }
            existing
        } else {
            wordId[node] = words.size
            words += display
            freqs += freq
            words.size - 1
        }
    }

    fun find(word: String): Int {
        var node = 0
        for (c in word.lowercase()) {
            node = child(node, c)
            if (node == -1) return -1
        }
        return wordId[node]
    }

    fun freqOf(word: String): Float = find(word).let { if (it < 0) -1f else freqs[it] }

    // Internal accessors for the search.
    internal fun firstChildOf(n: Int) = firstChild[n]
    internal fun nextSiblingOf(n: Int) = nextSibling[n]
    internal fun charOf(n: Int) = chars[n]
    internal fun wordOf(n: Int) = wordId[n]
    internal fun maxFreqOf(n: Int) = maxFreq[n]

    /** All words in id order (used to build glide-typing indices). */
    fun forEachWord(block: (id: Int, word: String, freq: Float) -> Unit) {
        for (i in words.indices) block(i, words[i], freqs[i])
    }
}
