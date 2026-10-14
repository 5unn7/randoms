package app.foldboard.core.text

import app.foldboard.core.layout.KeyboardLayout
import java.text.Normalizer
import java.util.PriorityQueue
import kotlin.math.min

/**
 * What the user actually typed: characters plus, when known, where each key was touched
 * (layout units). Touch points let the spatial model know that a tap on the right edge of
 * "r" was probably meant for "t".
 */
class TypedWord(val text: String, val xs: FloatArray, val ys: FloatArray) {
    val lower: String = text.lowercase()
    val length get() = text.length

    companion object {
        fun of(text: String) = TypedWord(text, FloatArray(text.length) { Float.NaN }, FloatArray(text.length) { Float.NaN })
    }
}

/** Edit costs for the weighted Damerau-Levenshtein search, aware of key geometry. */
class CostModel(var layout: KeyboardLayout? = null) {
    val transposition = 0.6f

    /** Cost of the user typing `typed[j]` where the dictionary has [c]. */
    fun sub(word: TypedWord, j: Int, c: Char): Float {
        val t = word.lower[j]
        if (t == c) return 0f
        if (fold(t) == fold(c)) return 0.05f // missing accent: cafe -> café
        val l = layout ?: return 1f
        val target = l.keyFor(c) ?: return 1f
        val x = word.xs[j]
        val y = word.ys[j]
        val d = if (!x.isNaN() && !y.isNaN()) l.distance(x, y, target)
        else l.keyFor(t)?.let { l.centerDistance(it, target) } ?: return 1f
        return (0.25f + 0.7f * (d - 0.5f)).coerceIn(0.25f, 1f)
    }

    /** Cost of a dictionary character the user did not type at all. */
    fun ins(c: Char, prevDictChar: Char): Float = when {
        c == '\'' -> 0.05f
        c == '-' -> 0.3f
        c == prevDictChar -> 0.15f // "leter" -> "letter": skipping a double letter is very common
        else -> 0.9f
    }

    /** Cost of an extra character the user typed. */
    fun del(word: TypedWord, j: Int): Float {
        val t = word.lower[j]
        return when {
            j > 0 && word.lower[j - 1] == t -> 0.4f // double tap
            t == '\'' || t == '-' -> 0.3f
            else -> 0.8f
        }
    }

    companion object {
        private val foldCache = HashMap<Char, Char>()
        fun fold(c: Char): Char = foldCache.getOrPut(c) {
            val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            (n.firstOrNull() ?: c).lowercaseChar().let { if (it == 'ß') 's' else it }
        }
    }
}

/** A dictionary hit: full match (typo-corrected word) or completion (word that starts like the input). */
data class Match(val id: Int, val cost: Float, val completion: Boolean)

/**
 * Error-tolerant lookup that walks the trie while maintaining one row of the edit-distance DP
 * per depth, pruning branches that can no longer come in under the cost budget.
 */
class FuzzySearch(private val trie: Trie, private val cost: CostModel) {

    private var rows = Array(MAX_DEPTH + 1) { FloatArray(32) }
    private val path = CharArray(MAX_DEPTH + 1)

    private lateinit var word: TypedWord
    private var n = 0
    private var maxCost = 0f
    private var completionCost = 0f
    private var maxCompletions = 0
    private var freqWeight = 1f
    private val full = HashMap<Int, Float>()
    private val completions = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
    private val completionIds = HashSet<Int>()

    @Synchronized
    fun search(
        word: TypedWord,
        maxCost: Float,
        completionCost: Float,
        maxCompletions: Int,
        costWeight: Float = 2.5f,
    ): List<Match> {
        this.word = word
        n = word.length
        if (n == 0 || n > MAX_DEPTH - 2) return emptyList()
        if (rows[0].size < n + 1) rows = Array(MAX_DEPTH + 1) { FloatArray(n + 8) }
        this.maxCost = maxCost
        this.completionCost = completionCost
        this.maxCompletions = maxCompletions
        this.freqWeight = costWeight
        full.clear(); completions.clear(); completionIds.clear()

        val r0 = rows[0]
        r0[0] = 0f
        for (j in 1..n) r0[j] = r0[j - 1] + cost.del(word, j - 1)

        var child = trie.firstChildOf(0)
        while (child != -1) {
            visit(child, 1, r0[n])
            child = trie.nextSiblingOf(child)
        }
        val out = ArrayList<Match>(full.size + completions.size)
        for ((id, c) in full) out += Match(id, c, false)
        for ((id, score) in completions) {
            // score = freq - w*cost  ->  recover cost
            val c = (trie.freqs[id] - score) / freqWeight
            out += Match(id, c, true)
        }
        return out
    }

    private fun visit(node: Int, depth: Int, parentPathMin: Float) {
        if (depth >= MAX_DEPTH) return
        val c = trie.charOf(node)
        path[depth - 1] = c
        val prev = rows[depth - 1]
        val cur = rows[depth]
        val prevDict = if (depth >= 2) path[depth - 2] else '\u0000'
        val insC = cost.ins(c, prevDict)
        cur[0] = prev[0] + insC
        var rowMin = cur[0]
        val lower = word.lower
        for (j in 1..n) {
            var v = prev[j - 1] + cost.sub(word, j - 1, c)
            val a = prev[j] + insC
            if (a < v) v = a
            val b = cur[j - 1] + cost.del(word, j - 1)
            if (b < v) v = b
            if (depth >= 2 && j >= 2 && lower[j - 1] == prevDict && lower[j - 2] == c && c != prevDict) {
                val t = rows[depth - 2][j - 2] + cost.transposition
                if (t < v) v = t
            }
            cur[j] = v
            if (v < rowMin) rowMin = v
        }
        val pathMin = min(parentPathMin, cur[n])
        val id = trie.wordOf(node)
        if (id >= 0) {
            if (cur[n] <= maxCost) {
                val old = full[id]
                if (old == null || cur[n] < old) full[id] = cur[n]
            }
            if (pathMin <= completionCost && depth > n) offerCompletion(id, pathMin)
        }
        val canFull = rowMin <= maxCost
        val canComplete = pathMin <= completionCost
        if (!canFull && !canComplete) return
        var child = trie.firstChildOf(node)
        while (child != -1) {
            if (canFull || !prunedByFrequency(child, pathMin)) visit(child, depth + 1, pathMin)
            child = trie.nextSiblingOf(child)
        }
    }

    private fun prunedByFrequency(node: Int, pathCost: Float): Boolean {
        if (completions.size < maxCompletions) return false
        val best = trie.maxFreqOf(node) - freqWeight * pathCost
        return best <= completions.peek().second
    }

    private fun offerCompletion(id: Int, pathCost: Float) {
        if (maxCompletions == 0 || id in completionIds) return
        val score = trie.freqs[id] - freqWeight * pathCost
        if (completions.size < maxCompletions) {
            completions.add(id to score); completionIds += id
        } else if (score > completions.peek().second) {
            completionIds -= completions.poll().first
            completions.add(id to score); completionIds += id
        }
    }

    companion object {
        const val MAX_DEPTH = 48
    }
}
