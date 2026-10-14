package app.foldboard.core.text

/**
 * Everything the keyboard learns from the user, kept on-device:
 *  - personal words (names, slang, jargon) with use counts
 *  - word pairs (bigrams) for next-word prediction
 *  - words the user explicitly rejected corrections for
 */
class LearningStore {
    val words = HashMap<String, Int>()
    val bigrams = HashMap<String, HashMap<String, Int>>()
    var dirty = false
        private set

    fun countOf(word: String) = words[word] ?: 0

    fun addWord(word: String, by: Int = 1) {
        if (word.isBlank() || word.length > 40) return
        words[word] = (words[word] ?: 0) + by
        dirty = true
    }

    fun removeWord(word: String) {
        if (words.remove(word) != null) dirty = true
    }

    fun addBigram(prev: String, next: String) {
        if (prev.isEmpty() || next.isEmpty()) return
        val m = bigrams.getOrPut(prev.lowercase()) { HashMap() }
        m[next] = (m[next] ?: 0) + 1
        dirty = true
    }

    fun bigramCount(prev: String?, next: String): Int =
        if (prev == null) 0 else bigrams[prev.lowercase()]?.get(next) ?: 0

    fun followers(prev: String): Map<String, Int> = bigrams[prev.lowercase()] ?: emptyMap()

    fun clear() {
        words.clear(); bigrams.clear(); dirty = true
    }

    fun serialize(): String {
        prune()
        val sb = StringBuilder()
        for ((w, c) in words) sb.append("w\t").append(w).append('\t').append(c).append('\n')
        for ((p, m) in bigrams) for ((n, c) in m) sb.append("b\t").append(p).append('\t').append(n).append('\t').append(c).append('\n')
        dirty = false
        return sb.toString()
    }

    fun load(text: String) {
        words.clear(); bigrams.clear()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            when {
                p.size == 3 && p[0] == "w" -> p[2].toIntOrNull()?.let { words[p[1]] = it }
                p.size == 4 && p[0] == "b" -> p[3].toIntOrNull()?.let { bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = it }
            }
        }
        dirty = false
    }

    /** Keep the store bounded: drop the rarest entries once it grows large. */
    private fun prune() {
        if (words.size > MAX_WORDS) {
            val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS).associate { it.key to it.value }
            words.clear(); words.putAll(keep)
        }
        val total = bigrams.values.sumOf { it.size }
        if (total > MAX_BIGRAMS) {
            for (m in bigrams.values) m.entries.removeIf { it.value <= 1 }
            bigrams.entries.removeIf { it.value.isEmpty() }
        }
    }

    companion object {
        const val MAX_WORDS = 20_000
        const val MAX_BIGRAMS = 60_000
    }
}
