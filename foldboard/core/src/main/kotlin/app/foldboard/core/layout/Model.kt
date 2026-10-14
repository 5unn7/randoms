package app.foldboard.core.layout

import kotlin.math.abs
import kotlin.math.sqrt

enum class KeyType {
    CHAR, SPACE, BACKSPACE, ENTER, SHIFT,
    SYMBOLS,      // letters -> ?123
    SYMBOLS_ALT,  // ?123 -> =\<  (and back)
    LETTERS,      // symbols -> ABC
    EMOJI, MIC, CLIPBOARD,
}

enum class KeyShape { HEX, RECT }

enum class Page { LETTERS, SYMBOLS, SYMBOLS_ALT }

/** Which cluster a key belongs to. Split layouts have a left and right half plus an optional center panel. */
enum class Cluster { MAIN, LEFT, CENTER, RIGHT }

/**
 * A single key. Geometry is in layout units: x is measured in key widths, y in rows,
 * (cx, cy) is the key center. The view scales units to pixels.
 */
data class Key(
    val type: KeyType,
    val label: String,
    val cx: Float,
    val cy: Float,
    val w: Float = 1f,
    val h: Float = 1f,
    val shape: KeyShape = KeyShape.HEX,
    /** Character produced by swiping down on the key (shown small in the corner). */
    val secondary: String? = null,
    /** Characters offered on long-press. */
    val alternates: List<String> = emptyList(),
    val cluster: Cluster = Cluster.MAIN,
) {
    val isLetter: Boolean get() = type == KeyType.CHAR && label.length == 1 && label[0].isLetter()
    val left get() = cx - w / 2f
    val right get() = cx + w / 2f
    val top get() = cy - h / 2f
    val bottom get() = cy + h / 2f
}

class KeyboardLayout(
    val keys: List<Key>,
    val width: Float,
    val rows: Int,
    val shape: KeyShape,
    val split: Boolean,
    val page: Page,
) {
    private val charCenters: Map<Char, Key> = buildMap {
        for (k in this@KeyboardLayout.keys) if (k.type == KeyType.CHAR && k.label.length == 1) {
            putIfAbsent(k.label[0].lowercaseChar(), k)
        }
    }

    /** Key for a (lowercase) character, if the character is printed on this layout. */
    fun keyFor(c: Char): Key? = charCenters[c.lowercaseChar()]

    /** Hit test. Hex layouts use a honeycomb Voronoi metric, rect layouts use containment with a nearest fallback. */
    fun keyAt(x: Float, y: Float): Key? {
        if (keys.isEmpty()) return null
        if (shape == KeyShape.RECT) {
            keys.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.let { return it }
        }
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val d = distance(x, y, k)
            if (d < bestD) { bestD = d; best = k }
        }
        // Touches in the empty gap between split halves land on nothing.
        return if (bestD <= 0.9f) best else null
    }

    /** Distance from a point to a key center in "key" units (honeycomb-corrected for hex). */
    fun distance(x: Float, y: Float, k: Key): Float {
        val dx = if (shape == KeyShape.RECT) {
            // Wide keys (space, shift) measure from their nearest edge region.
            val half = (k.w - 1f).coerceAtLeast(0f) / 2f
            (abs(x - k.cx) - half).coerceAtLeast(0f)
        } else x - k.cx
        val dy = (y - k.cy) * ROW_PITCH
        return sqrt(dx * dx + dy * dy)
    }

    fun centerDistance(a: Key, b: Key): Float {
        val dx = a.cx - b.cx
        val dy = (a.cy - b.cy) * ROW_PITCH
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Vertical distance between rows relative to key width that makes hex neighbours equidistant. */
        const val ROW_PITCH = 0.866f
    }
}
