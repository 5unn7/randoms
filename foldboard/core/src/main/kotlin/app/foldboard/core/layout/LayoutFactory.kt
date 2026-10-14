package app.foldboard.core.layout

/**
 * Letter arrangement. Each row is split into the part typed by the left hand and the right hand,
 * which is exactly how the split (unfolded) layouts divide it.
 */
data class Alphabet(val id: String, val displayName: String, val rows: List<Pair<String, String>>)

object Alphabets {
    val QWERTY = Alphabet("qwerty", "QWERTY", listOf("qwert" to "yuiop", "asdfg" to "hjkl", "zxcvb" to "nm"))
    val QWERTZ = Alphabet("qwertz", "QWERTZ", listOf("qwert" to "zuiop", "asdfg" to "hjkl", "yxcvb" to "nm"))
    val AZERTY = Alphabet("azerty", "AZERTY", listOf("azert" to "yuiop", "qsdfg" to "hjklm", "wxcvb" to "n"))
    val COLEMAK = Alphabet("colemak", "Colemak", listOf("qwfpg" to "jluy", "arstd" to "hneio", "zxcvb" to "km"))
    val DVORAK = Alphabet("dvorak", "Dvorak", listOf("',.py" to "fgcrl", "aoeui" to "dhtns", ";qjkx" to "bmwvz"))

    val all = listOf(QWERTY, QWERTZ, AZERTY, COLEMAK, DVORAK)
    fun byId(id: String?): Alphabet = all.firstOrNull { it.id == id } ?: QWERTY

    internal val SYMBOLS = Alphabet("sym", "?123", listOf("12345" to "67890", "@#\$_&" to "-+()", "*\"':" to ";!?/"))
    internal val SYMBOLS_ALT = Alphabet("sym2", "=\\<", listOf("~`|•√" to "π÷×¶∆", "£¢€¥^" to "°={}", "\\%©®" to "™✓[]"))
}

data class LayoutSpec(
    val alphabet: Alphabet = Alphabets.QWERTY,
    val page: Page = Page.LETTERS,
    val shape: KeyShape = KeyShape.HEX,
    val split: Boolean = false,
    /** Number/symbol panel between the halves (split only). */
    val centerPanel: Boolean = false,
    /** Empty space between the halves in key widths (split without panel). */
    val splitGap: Float = 3f,
    /** Show a dedicated mic key on the keyboard (it is always in the suggestion strip too). */
    val micKey: Boolean = true,
    /** Dedicated backspace key. Off by default: swipe left deletes. */
    val backspaceKey: Boolean = false,
)

object LayoutFactory {

    private data class Cell(val type: KeyType, val label: String = "", val w: Float = 1f)

    private fun ch(s: String) = Cell(KeyType.CHAR, s)
    private fun chars(s: String) = s.map { ch(it.toString()) }

    private val LETTER_FILLERS = listOf(",", ".", "?", "'", "-", "!", ";", ":", "/")
    private val SYMBOL_FILLERS = listOf("<", ">", "[", "]", "{", "}", "=", "|", "%")

    /** Swipe-down characters by row slot, as on most phone keyboards' long-press hints. */
    private val SECONDARY_ROWS = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?~`|")

    private val ACCENTS: Map<Char, String> = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "èéêëēęė", 'i' to "ìíîïī", 'n' to "ñń",
        'o' to "òóôöõøœō", 's' to "ßśš", 'u' to "ùúûüū", 'y' to "ÿý", 'z' to "žźż", 'l' to "ł",
        'g' to "ğ", 'd' to "ð", 't' to "þ", 'r' to "ř",
    )
    private val PUNCT_ALTERNATES: Map<String, String> = mapOf(
        "." to "…·", "," to ";", "?" to "¿!", "!" to "¡", "'" to "\"‘’“”«»", "-" to "–—_",
        "$" to "€£¥¢₹₩", "%" to "‰", "+" to "±", "=" to "≠≈", "/" to "\\÷", "*" to "×†★",
        "(" to "[{<", ")" to "]}>", "&" to "§", "#" to "№", "0" to "°∅", "1" to "¹½⅓¼",
        "2" to "²⅔", "3" to "³¾", "<" to "≤«", ">" to "≥»", "^" to "ˆ", "~" to "≈",
    )

    fun build(spec: LayoutSpec): KeyboardLayout =
        if (spec.shape == KeyShape.HEX) buildHex(spec) else buildRect(spec)

    // ---------------------------------------------------------------- hex (honeycomb)

    private fun pageRows(spec: LayoutSpec): List<Pair<String, String>> = when (spec.page) {
        Page.LETTERS -> spec.alphabet.rows
        Page.SYMBOLS -> Alphabets.SYMBOLS.rows
        Page.SYMBOLS_ALT -> Alphabets.SYMBOLS_ALT.rows
    }

    private fun shiftCell(page: Page) = when (page) {
        Page.LETTERS -> Cell(KeyType.SHIFT, "shift")
        Page.SYMBOLS -> Cell(KeyType.SYMBOLS_ALT, "=\\<")
        Page.SYMBOLS_ALT -> Cell(KeyType.SYMBOLS_ALT, "?123")
    }

    private fun pageCell(page: Page) =
        if (page == Page.LETTERS) Cell(KeyType.SYMBOLS, "?123") else Cell(KeyType.LETTERS, "ABC")

    private fun fillers(page: Page) = if (page == Page.LETTERS) LETTER_FILLERS else SYMBOL_FILLERS

    private fun buildHex(spec: LayoutSpec): KeyboardLayout {
        val rows = pageRows(spec)
        val page = spec.page
        val (l0, r0) = rows[0]; val (l1, r1) = rows[1]; val (l2, r2) = rows[2]
        val clusters = ArrayList<Pair<List<List<Cell>>, Cluster>>()
        val mic = if (spec.micKey) listOf(Cell(KeyType.MIC, "mic")) else emptyList()
        val back = if (spec.backspaceKey) listOf(Cell(KeyType.BACKSPACE)) else emptyList()
        if (!spec.split) {
            val punct = if (page == Page.LETTERS) listOf("'", "?") else listOf(",", ".")
            val g = mutableListOf(
                chars(l0 + r0).toMutableList(),
                (chars(l1) + Cell(KeyType.SPACE) + chars(r1) + back).toMutableList(),
                (listOf(shiftCell(page)) + chars(l2 + r2)).toMutableList(),
                (listOf(pageCell(page), Cell(KeyType.EMOJI, "emoji")) + mic + listOf(ch(punct[0]), Cell(KeyType.SPACE), Cell(KeyType.SPACE), ch(punct[1]), Cell(KeyType.CLIPBOARD, "clip"), Cell(KeyType.ENTER, "enter"))).toMutableList(),
            )
            normalizeHex(g, fillers(page))
            clusters += g to Cluster.MAIN
        } else {
            val lp = if (page == Page.LETTERS) "-" else ","
            val rp = if (page == Page.LETTERS) "'" else "."
            val left = mutableListOf(
                chars(l0).toMutableList(),
                chars(l1).toMutableList().also { it.add(minOf(2, it.size), Cell(KeyType.SPACE)) },
                chars(l2).toMutableList(),
                mutableListOf(shiftCell(page), pageCell(page), Cell(KeyType.EMOJI, "emoji"), ch(lp), Cell(KeyType.SPACE), Cell(KeyType.SPACE)),
            )
            val right = mutableListOf(
                chars(r0).toMutableList(),
                chars(r1).toMutableList().also { it.add(minOf(2, it.size), Cell(KeyType.SPACE)); it.addAll(back) },
                chars(r2).toMutableList(),
                (listOf(Cell(KeyType.SPACE), Cell(KeyType.SPACE)) + mic + listOf(ch(rp), Cell(KeyType.CLIPBOARD, "clip"), Cell(KeyType.ENTER, "enter"))).toMutableList(),
            )
            val used = (left + right).flatten().filter { it.type == KeyType.CHAR }.map { it.label }.toMutableSet()
            val pool = fillers(page).filter { it !in used }.toMutableList()
            while (left[2].size < left[0].size) left[2].add(ch(pool.removeFirstOr("-")))
            while (right[2].size < right[0].size) right[2].add(ch(pool.removeFirstOr("-")))
            normalizeHex(left, pool)
            normalizeHex(right, pool)
            clusters += left to Cluster.LEFT
            if (spec.centerPanel) clusters += hexPanel() to Cluster.CENTER
            clusters += right to Cluster.RIGHT
        }
        return assemble(clusters, KeyShape.HEX, spec)
    }

    private fun hexPanel(): List<List<Cell>> = listOf(
        chars("$123%"),
        chars("-456+="),
        chars("/789*"),
        chars("&,0.#:"),
    )

    private fun <T> MutableList<T>.removeFirstOr(default: T): T = if (isEmpty()) default else removeAt(0)

    /**
     * Make a honeycomb: consecutive rows must differ in parity of their cell count so that, when
     * centered, every other row is shifted by half a key.
     */
    private fun normalizeHex(rows: MutableList<MutableList<Cell>>, fillerSource: List<String>) {
        val used = rows.flatten().filter { it.type == KeyType.CHAR }.map { it.label }.toMutableSet()
        val pool = fillerSource.filter { it !in used }.toMutableList()
        val last = rows.size - 1
        // Bottom (function) row and the row with the shift key should be at least as wide as their
        // same-parity neighbours, so the honeycomb doesn't look ragged.
        for (i in 2..last) {
            val target = rows[i - 2].size
            while (rows[i].size < target) pad(rows[i], i == last, pool)
        }
        for (i in 1..last) {
            val want = (rows[0].size + i) % 2
            if (rows[i].size % 2 != want) pad(rows[i], i == last, pool)
        }
    }

    private fun pad(row: MutableList<Cell>, functionRow: Boolean, pool: MutableList<String>) {
        if (functionRow) {
            val idx = row.indexOfFirst { it.type == KeyType.SPACE }
            row.add(if (idx >= 0) idx else row.size, Cell(KeyType.SPACE))
        } else {
            val lastChar = row.indexOfLast { it.type == KeyType.CHAR }
            row.add(lastChar + 1, ch(pool.removeFirstOr("-")))
        }
    }

    // ---------------------------------------------------------------- classic (rectangles)

    private fun buildRect(spec: LayoutSpec): KeyboardLayout {
        val rows = pageRows(spec)
        val page = spec.page
        val (l0, r0) = rows[0]; val (l1, r1) = rows[1]; val (l2, r2) = rows[2]
        val shift = shiftCell(page).copy(w = 1.5f)
        val back = if (spec.backspaceKey) listOf(Cell(KeyType.BACKSPACE, w = 1.5f)) else emptyList()
        val clusters = ArrayList<Pair<List<List<Cell>>, Cluster>>()
        if (!spec.split) {
            val top = chars(l0 + r0)
            val mid = chars(l1 + r1)
            val bot = listOf(shift) + chars(l2 + r2) + back
            val width = maxOf(top.sumW(), mid.sumW(), bot.sumW())
            val fixed = listOf(pageCell(page).copy(w = 1.5f), Cell(KeyType.EMOJI, "emoji"), ch(","))
            val tail = (if (spec.micKey) listOf(ch("."), Cell(KeyType.MIC, "mic")) else listOf(ch("."))) + Cell(KeyType.ENTER, "enter", 1.5f)
            val space = Cell(KeyType.SPACE, "space", (width - fixed.sumW() - tail.sumW()).coerceAtLeast(2f))
            clusters += listOf(top, mid, bot, fixed + space + tail) to Cluster.MAIN
        } else {
            val leftRows = listOf(chars(l0), chars(l1), listOf(shift) + chars(l2))
            val rightRows = listOf(chars(r0), chars(r1), chars(r2) + back)
            val lw = leftRows.maxOf { it.sumW() }.coerceAtLeast(5.5f)
            val rw = rightRows.maxOf { it.sumW() }.coerceAtLeast(5.5f)
            val lFixed = listOf(pageCell(page).copy(w = 1.5f), Cell(KeyType.EMOJI, "emoji"), ch(","))
            val rFixed = (if (spec.micKey) listOf(ch("."), Cell(KeyType.MIC, "mic")) else listOf(ch("."))) + Cell(KeyType.ENTER, "enter", 1.5f)
            clusters += (leftRows + listOf(lFixed + Cell(KeyType.SPACE, "space", (lw - lFixed.sumW()).coerceAtLeast(1.5f)))) to Cluster.LEFT
            if (spec.centerPanel) clusters += listOf(chars("123-"), chars("456+"), chars("789*"), chars(",0./")) to Cluster.CENTER
            clusters += (rightRows + listOf(listOf(Cell(KeyType.SPACE, "space", (rw - rFixed.sumW()).coerceAtLeast(1.5f))) + rFixed)) to Cluster.RIGHT
        }
        return assemble(clusters, KeyShape.RECT, spec)
    }

    private fun List<Cell>.sumW() = sumOf { it.w.toDouble() }.toFloat()

    // ---------------------------------------------------------------- assembly

    private fun assemble(clusters: List<Pair<List<List<Cell>>, Cluster>>, shape: KeyShape, spec: LayoutSpec): KeyboardLayout {
        val keys = ArrayList<Key>()
        val rowCount = clusters.maxOf { it.first.size }
        var x0 = 0f
        clusters.forEachIndexed { ci, (rows, cluster) ->
            if (ci > 0) {
                val prev = clusters[ci - 1].second
                x0 += when {
                    cluster == Cluster.CENTER || prev == Cluster.CENTER -> 0.35f
                    else -> spec.splitGap
                }
            }
            val clusterW = rows.maxOf { if (shape == KeyShape.HEX) it.size.toFloat() else it.sumW() }
            rows.forEachIndexed { r, row ->
                val rowW = if (shape == KeyShape.HEX) row.size.toFloat() else row.sumW()
                var x = x0 + (clusterW - rowW) / 2f
                val secondaries = if (spec.page == Page.LETTERS) SECONDARY_ROWS.getOrNull(r) else null
                var charSlot = 0
                for (cell in row) {
                    val cx = x + cell.w / 2f
                    val secondary = if (cell.type == KeyType.CHAR && cluster != Cluster.CENTER && cell.label[0].isLetter()) {
                        secondaryFor(spec, cluster, r, charSlot, secondaries)
                    } else null
                    if (cell.type == KeyType.CHAR && cell.label[0].isLetter()) charSlot++
                    keys += Key(
                        type = cell.type,
                        label = cell.label,
                        cx = cx,
                        cy = r + 0.5f,
                        w = cell.w,
                        h = 1f,
                        shape = shape,
                        secondary = secondary,
                        alternates = if (cell.type == KeyType.CHAR) alternatesFor(cell.label, secondary) else emptyList(),
                        cluster = cluster,
                    )
                    x += cell.w
                }
            }
            x0 += clusterW
        }
        return KeyboardLayout(keys, x0, rowCount, shape, spec.split, spec.page)
    }

    /** Secondary by position in the full (unsplit) row so it is the same in folded and unfolded modes. */
    private fun secondaryFor(spec: LayoutSpec, cluster: Cluster, row: Int, slot: Int, secondaries: String?): String? {
        if (secondaries == null) return null
        val leftLetters = spec.alphabet.rows.getOrNull(row)?.first?.count { it.isLetter() } ?: 0
        val idx = if (cluster == Cluster.RIGHT) leftLetters + slot else slot
        return secondaries.getOrNull(idx)?.toString()
    }

    private fun alternatesFor(label: String, secondary: String?): List<String> {
        val out = ArrayList<String>()
        if (secondary != null) out += secondary
        val c = label[0]
        ACCENTS[c.lowercaseChar()]?.forEach { out += it.toString() }
        PUNCT_ALTERNATES[label]?.forEach { out += it.toString() }
        return out
    }
}
