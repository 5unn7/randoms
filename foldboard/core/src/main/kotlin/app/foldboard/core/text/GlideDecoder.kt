package app.foldboard.core.text

import app.foldboard.core.layout.KeyboardLayout
import app.foldboard.core.layout.KeyboardLayout.Companion.ROW_PITCH
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Glide (shape-writing) decoder. Compares the finger path with the ideal path through each
 * candidate word's keys (shape channel) and checks that every letter's key was actually
 * passed near (location channel), then mixes in word frequency.
 *
 * Candidates are bucketed by (first key, last key) so a decode only scores a few thousand words.
 */
class GlideDecoder(private val trie: Trie) {
    private var layout: KeyboardLayout? = null
    private var index: HashMap<Int, IntArray>? = null

    fun setLayout(l: KeyboardLayout?) {
        if (l !== layout) { layout = l; index = null }
    }

    private fun keyChar(c: Char): Char? {
        val l = layout ?: return null
        val lc = c.lowercaseChar()
        if (l.keyFor(lc) != null) return lc
        val f = CostModel.fold(lc)
        return if (l.keyFor(f) != null) f else null
    }

    private fun bucket(a: Char, b: Char) = (a.code shl 16) or b.code

    private fun buildIndex(): HashMap<Int, IntArray> {
        val tmp = HashMap<Int, ArrayList<Int>>()
        trie.forEachWord { id, word, _ ->
            if (word.length < 2 || word.any { it == '\'' || it == '-' }) return@forEachWord
            val first = keyChar(word.first()) ?: return@forEachWord
            val last = keyChar(word.last()) ?: return@forEachWord
            if (word.any { keyChar(it) == null }) return@forEachWord
            tmp.getOrPut(bucket(first, last)) { ArrayList() } += id
        }
        val out = HashMap<Int, IntArray>(tmp.size)
        for ((k, ids) in tmp) {
            ids.sortByDescending { trie.freqs[it] }
            out[k] = ids.take(MAX_PER_BUCKET).toIntArray()
        }
        return out
    }

    /** @param xs, ys finger path in layout units. Returns best words, best first. */
    fun decode(xs: FloatArray, ys: FloatArray, count: Int, limit: Int = 5): List<String> {
        val l = layout ?: return emptyList()
        if (count < 2) return emptyList()
        val idx = index ?: buildIndex().also { index = it }
        val path = resample(xs, ys.map { it * ROW_PITCH }.toFloatArray(), count, N)
        val pathLen = length(xs, ys, count)
        val starts = nearestLetters(l, xs[0], ys[0])
        val ends = nearestLetters(l, xs[count - 1], ys[count - 1])
        val scored = ArrayList<Pair<String, Float>>()
        val tx = FloatArray(64); val ty = FloatArray(64)
        for (s in starts) for (e in ends) {
            val ids = idx[bucket(s, e)] ?: continue
            for (id in ids) {
                val word = trie.words[id]
                var m = 0
                var lastC = ' '
                for (ch in word) {
                    val kc = keyChar(ch) ?: continue
                    if (kc == lastC) continue
                    lastC = kc
                    val k = l.keyFor(kc) ?: continue
                    if (m < tx.size) { tx[m] = k.cx; ty[m] = k.cy; m++ }
                }
                if (m == 0) continue
                val tLen = length(tx, ty, m)
                // Ignore words whose ideal path is far longer or shorter than the stroke.
                if (tLen > 0.5f && pathLen > 0.5f && abs(ln((tLen / pathLen).toDouble())) > 0.8) continue
                val template = resample(tx, FloatArray(m) { ty[it] * ROW_PITCH }, m, N)
                var shape = 0f
                for (i in 0 until N) shape += dist(path[0][i], path[1][i], template[0][i], template[1][i])
                shape /= N
                var miss = 0f
                for (i in 0 until m) {
                    var best = Float.MAX_VALUE
                    val kx = tx[i]; val ky = ty[i] * ROW_PITCH
                    for (j in 0 until N) { val d = dist(kx, ky, path[0][j], path[1][j]); if (d < best) best = d }
                    if (best > 0.6f) miss += best - 0.6f
                }
                val score = 0.45f * trie.freqs[id] - 3.2f * shape - 2.5f * miss
                scored += word to score
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
    }

    private fun nearestLetters(l: KeyboardLayout, x: Float, y: Float): List<Char> =
        l.keys.asSequence().filter { it.isLetter }
            .map { it to l.distance(x, y, it) }
            .filter { it.second <= 1.15f }
            .sortedBy { it.second }
            .take(4)
            .map { it.first.label[0].lowercaseChar() }
            .toList()

    companion object {
        const val N = 32
        const val MAX_PER_BUCKET = 700

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }

        private fun length(xs: FloatArray, ys: FloatArray, n: Int): Float {
            var s = 0f
            for (i in 1 until n) s += dist(xs[i - 1], ys[i - 1] * ROW_PITCH, xs[i], ys[i] * ROW_PITCH)
            return s
        }

        /** Resample a polyline to [n] equidistant points. Returns [xs, ys]. */
        fun resample(xs: FloatArray, ys: FloatArray, count: Int, n: Int): Array<FloatArray> {
            val ox = FloatArray(n); val oy = FloatArray(n)
            if (count == 1) { ox.fill(xs[0]); oy.fill(ys[0]); return arrayOf(ox, oy) }
            var total = 0f
            for (i in 1 until count) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            if (total == 0f) { ox.fill(xs[0]); oy.fill(ys[0]); return arrayOf(ox, oy) }
            val step = total / (n - 1)
            ox[0] = xs[0]; oy[0] = ys[0]
            var out = 1
            var acc = 0f
            var px = xs[0]; var py = ys[0]
            var i = 1
            while (i < count && out < n) {
                val d = dist(px, py, xs[i], ys[i])
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    px += t * (xs[i] - px); py += t * (ys[i] - py)
                    ox[out] = px; oy[out] = py; out++
                    acc = 0f
                } else {
                    acc += d
                    px = xs[i]; py = ys[i]
                    i++
                }
            }
            while (out < n) { ox[out] = xs[count - 1]; oy[out] = ys[count - 1]; out++ }
            return arrayOf(ox, oy)
        }
    }
}
