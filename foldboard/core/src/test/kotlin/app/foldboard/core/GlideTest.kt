package app.foldboard.core

import app.foldboard.core.gesture.*
import app.foldboard.core.layout.*
import app.foldboard.core.text.*
import kotlin.random.Random
import kotlin.test.*

class GlideTest {
    private val layout = LayoutFactory.build(LayoutSpec())
    private val decoder = GlideDecoder(TextEngineTest.en).also { it.setLayout(layout) }

    /** Simulate a finger gliding through the word's keys with some wobble. */
    private fun stroke(word: String, rnd: Random): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val keys = word.map { layout.keyFor(it)!! }
        for (i in 0 until keys.size - 1) {
            val a = keys[i]; val b = keys[i + 1]
            for (s in 0 until 8) {
                val t = s / 8f
                xs += a.cx + (b.cx - a.cx) * t + (rnd.nextFloat() - 0.5f) * 0.3f
                ys += a.cy + (b.cy - a.cy) * t + (rnd.nextFloat() - 0.5f) * 0.3f
            }
        }
        xs += keys.last().cx; ys += keys.last().cy
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun decodesCommonWords() {
        val rnd = Random(7)
        val words = listOf("hello", "world", "keyboard", "thanks", "great", "people", "would", "phone", "fold", "typing")
        val misses = words.filter { w ->
            val (xs, ys) = stroke(w, rnd)
            val top = decoder.decode(xs, ys, xs.size, 3)
            w !in top
        }
        assertTrue(misses.size <= 1, "missed $misses")
    }

    @Test fun classifiesSwipes() {
        assertEquals(Gesture.TAP, GestureClassifier.classify(0.1f, 0.2f))
        assertEquals(Gesture.SWIPE_UP, GestureClassifier.classify(0.1f, -0.9f))
        assertEquals(Gesture.SWIPE_DOWN, GestureClassifier.classify(0.2f, 0.9f))
        assertEquals(Gesture.SWIPE_LEFT, GestureClassifier.classify(-1.5f, 0.2f))
        assertEquals(Gesture.SWIPE_RIGHT, GestureClassifier.classify(1.5f, 0.2f))
    }
}
