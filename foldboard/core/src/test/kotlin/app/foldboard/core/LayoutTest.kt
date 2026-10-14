package app.foldboard.core

import app.foldboard.core.layout.*
import kotlin.math.abs
import kotlin.test.*

class LayoutTest {
    private val specs = buildList {
        for (a in Alphabets.all) for (shape in KeyShape.entries) for (split in listOf(false, true)) for (panel in listOf(false, true)) for (page in Page.entries) for (back in listOf(false, true))
            add(LayoutSpec(alphabet = a, page = page, shape = shape, split = split, centerPanel = panel && split, backspaceKey = back))
    }

    @Test fun everyLetterIsReachableOnEveryLetterLayout() {
        for (s in specs.filter { it.page == Page.LETTERS }) {
            val l = LayoutFactory.build(s)
            for (c in 'a'..'z') assertNotNull(l.keyFor(c), "$c missing in $s")
        }
    }

    @Test fun essentialKeysPresent() {
        for (s in specs) {
            val types = LayoutFactory.build(s).keys.map { it.type }.toSet()
            for (t in listOf(KeyType.SPACE, KeyType.ENTER, KeyType.EMOJI)) assertTrue(t in types, "$t missing in $s")
            assertEquals(s.backspaceKey, KeyType.BACKSPACE in types, "$s")
            if (s.page == Page.LETTERS) assertTrue(KeyType.SHIFT in types && KeyType.SYMBOLS in types, "$s")
            else assertTrue(KeyType.LETTERS in types, "$s")
        }
    }

    @Test fun hexRowsFormAHoneycomb() {
        for (s in specs.filter { it.shape == KeyShape.HEX }) {
            val l = LayoutFactory.build(s)
            for (cluster in l.keys.map { it.cluster }.distinct()) {
                val rows = l.keys.filter { it.cluster == cluster }.groupBy { it.cy }.toSortedMap().values.toList()
                for (i in 1 until rows.size) {
                    val off = abs(rows[i].minOf { it.cx } - rows[i - 1].minOf { it.cx }) % 1f
                    assertEquals(0.5f, off, 0.01f, "rows ${i - 1}/$i of $cluster not staggered in $s")
                }
            }
        }
    }

    @Test fun keysDoNotOverlap() {
        for (s in specs) {
            val keys = LayoutFactory.build(s).keys
            for (a in keys.indices) for (b in a + 1 until keys.size) {
                val k1 = keys[a]; val k2 = keys[b]
                if (k1.cy != k2.cy) continue
                assertTrue(k1.right <= k2.left + 0.001f || k2.right <= k1.left + 0.001f, "$k1 overlaps $k2 in $s")
            }
        }
    }

    @Test fun hitTestFindsKeyAtItsCenter() {
        for (s in specs) {
            val l = LayoutFactory.build(s)
            for (k in l.keys) assertSame(k, l.keyAt(k.cx, k.cy), "hit test failed for ${k.label} in $s")
        }
    }

    @Test fun splitGapIsDead() {
        val l = LayoutFactory.build(LayoutSpec(split = true, splitGap = 4f))
        val left = l.keys.filter { it.cluster == Cluster.LEFT }.maxOf { it.right }
        assertNull(l.keyAt(left + 2f, 1.5f))
    }

    @Test fun topRowSecondariesAreDigitsInBothModes() {
        for (split in listOf(false, true)) {
            val l = LayoutFactory.build(LayoutSpec(split = split))
            assertEquals("1", l.keyFor('q')!!.secondary)
            assertEquals("6", l.keyFor('y')!!.secondary)
            assertEquals("0", l.keyFor('p')!!.secondary)
        }
    }

    @Test fun spaceSitsInsideTheLetterCluster() {
        val l = LayoutFactory.build(LayoutSpec(split = true))
        val spaces = l.keys.filter { it.type == KeyType.SPACE && it.cy == 1.5f }
        assertEquals(2, spaces.size, "one center space per half")
    }
}
