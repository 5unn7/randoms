package app.foldboard.core

import app.foldboard.core.layout.*
import app.foldboard.core.text.*
import java.io.File
import kotlin.test.*

class TextEngineTest {
    companion object {
        val dictDir = File(System.getProperty("foldboard.dictDir") ?: "../app/src/main/assets/dict")
        val en: Trie by lazy { TextEngine.loadDictionary(File(dictDir, "en.txt").readLines().asSequence()) }
        val fr: Trie by lazy { TextEngine.loadDictionary(File(dictDir, "fr.txt").readLines().asSequence()) }
        val qwerty = LayoutFactory.build(LayoutSpec())
    }

    private fun engine(lang: String = "en", trie: Trie = en) = TextEngine(lang, trie).also { it.setLayout(qwerty) }

    private fun corrected(e: TextEngine, typed: String, prev: String? = null) =
        e.suggest(TypedWord.of(typed), prev).primary

    @Test fun fixesClassicTypos() {
        val e = engine()
        val cases = mapOf(
            "teh" to "the", "hte" to "the", "adn" to "and", "recieve" to "receive", "becuase" to "because",
            "helo" to "hello", "hrllo" to "hello", "tge" to "the", "wierd" to "weird", "thier" to "their",
            "dont" to "don't", "im" to "I'm", "i" to "I", "cant" to "can't", "youre" to "you're",
            "keybaord" to "keyboard", "beautifull" to "beautiful", "tommorow" to "tomorrow",
            "Teh" to "The", "HELO" to "HELLO",
        )
        val failures = cases.filter { (typed, want) -> corrected(e, typed) != want }
            .map { (typed, want) -> "$typed -> ${corrected(e, typed)} (want $want)" }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun leavesValidWordsAlone() {
        val e = engine()
        for (w in listOf("the", "its", "it's", "were", "well", "ill", "form", "from", "lets", "hello", "London", "iPhone", "abc123", "a"))
            assertEquals(w, corrected(e, w), "should not touch $w")
    }

    @Test fun usesTouchPositionsToDisambiguate() {
        val e = engine()
        // "tine": tap meant for 'i' landed near 'o' boundary? We type "rhe" with touches on the
        // t/r and g/h borders -> "the".
        val r = qwerty.keyFor('r')!!; val t = qwerty.keyFor('t')!!
        val h = qwerty.keyFor('h')!!; val e2 = qwerty.keyFor('e')!!
        val word = TypedWord("rhe", floatArrayOf((r.cx + t.cx) / 2 + 0.05f, h.cx, e2.cx), floatArrayOf(r.cy, h.cy, e2.cy))
        assertEquals("the", e.suggest(word, null).primary)
    }

    @Test fun completesWords() {
        val e = engine()
        val r = e.suggest(TypedWord.of("tha"), null)
        assertTrue(r.alternatives.any { it == "that" || it == "thanks" || it == "thank" }, r.toString())
        val k = e.suggest(TypedWord.of("keyb"), null)
        assertTrue("keyboard" in k.alternatives, k.toString())
    }

    @Test fun learnsPersonalWords() {
        val e = engine()
        assertFalse("Zorblax" in e.suggest(TypedWord.of("Zorbl"), null).alternatives)
        e.learn("Zorblax", null); e.learn("Zorblax", null)
        assertEquals("Zorblax", corrected(e, "Zorblax"))
        val r = e.suggest(TypedWord.of("Zorbl"), null)
        assertTrue("Zorblax" in r.alternatives + r.primary, r.toString())
    }

    @Test fun rejectingACorrectionSticks() {
        val e = engine()
        val first = corrected(e, "fooz")
        assertNotEquals("fooz", first)
        e.rejectCorrection("fooz")
        assertEquals("fooz", corrected(e, "fooz"))
    }

    @Test fun predictsNextWordsAndLearnsPairs() {
        val e = engine()
        assertTrue("you" in e.predict("thank"))
        repeat(3) { e.learn("pizza", "love") }
        assertEquals("pizza", e.predict("love").first())
        assertEquals(listOf("I", "The"), e.predict(null).take(2))
    }

    @Test fun learningRoundTrips() {
        val e = engine()
        e.learn("Zorblax", "hi"); e.learn("Zorblax", "hi")
        val text = e.learning.serialize()
        val s2 = LearningStore().also { it.load(text) }
        assertEquals(2, s2.countOf("Zorblax"))
        assertEquals(2, s2.bigramCount("hi", "Zorblax"))
    }

    @Test fun accentInsensitiveInFrench() {
        val e = engine("fr", fr)
        assertEquals("café", corrected(e, "cafe"))
        assertEquals("très", corrected(e, "tres"))
    }

    @Test fun autocorrectOffNeverChanges() {
        val e = engine()
        assertEquals("teh", e.suggest(TypedWord.of("teh"), null, level = AutocorrectLevel.OFF).primary)
    }

    @Test fun searchIsFast() {
        val e = engine()
        val words = listOf("keybaord", "beautifull", "th", "tomorow", "accomodation", "internationl", "a", "probablly")
        repeat(3) { words.forEach { e.suggest(TypedWord.of(it), "the") } } // warm up
        val t0 = System.nanoTime()
        repeat(10) { words.forEach { e.suggest(TypedWord.of(it), "the") } }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / (10 * words.size)
        println("suggest() avg %.2f ms".format(perCallMs))
        assertTrue(perCallMs < 25, "too slow: $perCallMs ms")
    }
}
