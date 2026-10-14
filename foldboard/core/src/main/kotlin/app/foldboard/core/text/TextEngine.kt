package app.foldboard.core.text

import app.foldboard.core.layout.KeyboardLayout
import kotlin.math.ln
import kotlin.math.min

enum class AutocorrectLevel { OFF, MILD, NORMAL, AGGRESSIVE }

data class Candidate(val word: String, val score: Float, val cost: Float, val completion: Boolean)

/**
 * @param primary what pressing space will commit (the correction, or the typed word itself)
 * @param corrected true when [primary] differs from what was typed
 * @param alternatives other suggestions, best first, not including [primary] or the typed word
 */
data class SuggestionResult(
    val typed: String,
    val primary: String,
    val corrected: Boolean,
    val alternatives: List<String>,
    val typedIsValid: Boolean,
)

/**
 * The brain: autocorrect, completions, next-word prediction and learning for one language.
 * Pure Kotlin so it is unit-tested on the JVM; the Android service owns one instance.
 */
class TextEngine(
    val language: String,
    private val main: Trie,
    val learning: LearningStore = LearningStore(),
) {
    val cost = CostModel()
    private val mainSearch = FuzzySearch(main, cost)
    private var user = Trie()
    private var userSearch = FuzzySearch(user, cost)
    /** Words whose correction the user undid this session: never "fix" them again. */
    private val ignored = HashSet<String>()

    init { rebuildUserLexicon() }

    private val glide = GlideDecoder(main)

    @Synchronized
    fun setLayout(layout: KeyboardLayout?) { cost.layout = layout; glide.setLayout(layout) }

    /** Decode a glide stroke (layout units) into candidate words, best first. */
    @Synchronized
    fun decodeGlide(xs: FloatArray, ys: FloatArray, count: Int, prev: String?, limit: Int = 5): List<String> {
        val words = glide.decode(xs, ys, count, limit * 2)
        // Light re-rank with personal usage and context.
        return words.withIndex()
            .sortedByDescending { (i, w) -> -i * 0.6f + personalBonus(w) + contextBonus(prev, w) }
            .map { it.value }
            .take(limit)
    }

    @Synchronized
    fun rebuildUserLexicon() {
        user = Trie()
        for ((w, c) in learning.words) if (isLearned(w, c)) user.put(w, userFreq(c))
        userSearch = FuzzySearch(user, cost)
    }

    private fun isLearned(word: String, count: Int) = count >= 2 && main.find(word) < 0

    private fun userFreq(count: Int) = 3.8f + min(1.5f, 0.5f * log2(count.toFloat()))

    @Synchronized
    fun isKnown(word: String): Boolean {
        val lower = word.lowercase()
        return main.find(lower) >= 0 || user.find(lower) >= 0 || lower in ignored
    }

    // ------------------------------------------------------------------ suggestions

    @Synchronized
    fun suggest(word: TypedWord, prev: String?, limit: Int = 5, level: AutocorrectLevel = AutocorrectLevel.NORMAL): SuggestionResult {
        val typed = word.text
        val lower = word.lower
        val n = word.length
        val searchMax = when { n <= 2 -> 1.0f; n <= 4 -> 1.6f; else -> 2.2f }
        val completionMax = when { n <= 1 -> 0f; n <= 3 -> 0.5f; else -> 1.0f }

        val byWord = LinkedHashMap<String, Candidate>()   // best showing per word, for the strip
        val fullByWord = HashMap<String, Candidate>()      // best typo-match per word, for autocorrect
        fun consider(display: String, freq: Float, m: Match) {
            var score = freq - COST_WEIGHT * m.cost
            if (m.completion) score -= 0.35f + 0.05f * (display.length - n).coerceAtLeast(0)
            score += personalBonus(display) + contextBonus(prev, display)
            val key = display.lowercase()
            val c = Candidate(display, score, m.cost, m.completion)
            val old = byWord[key]
            if (old == null || score > old.score) byWord[key] = c
            if (!m.completion) {
                val oldFull = fullByWord[key]
                if (oldFull == null || score > oldFull.score) fullByWord[key] = c
            }
        }
        for (m in mainSearch.search(word, searchMax, completionMax, 8, COST_WEIGHT)) consider(main.words[m.id], main.freqs[m.id], m)
        for (m in userSearch.search(word, searchMax, completionMax, 4, COST_WEIGHT)) consider(user.words[m.id], user.freqs[m.id], m)

        val ranked = byWord.values.sortedByDescending { it.score }
        val typedIsValid = isValidAsTyped(typed)
        val full = fullByWord.values.sortedByDescending { it.score }
        val correction = if (level == AutocorrectLevel.OFF) null else chooseCorrection(word, typedIsValid, full, level)
        val primary = correction ?: typed
        val alternatives = ranked.asSequence()
            .map { applyCase(typed, it.word) }
            .filter { !it.equals(primary, ignoreCase = false) && !it.equals(typed, ignoreCase = true) }
            .distinct()
            .take(limit)
            .toList()
        return SuggestionResult(typed, primary, correction != null && correction != typed, alternatives, typedIsValid)
    }

    private fun isValidAsTyped(typed: String): Boolean {
        if (typed.any { !it.isLetter() && it != '\'' && it != '-' }) return true // codes, numbers, handles
        if (typed.length > 1 && typed.drop(1).any { it.isUpperCase() } && !typed.all { !it.isLetter() || it.isUpperCase() }) return true // iPhone, McDonald
        return isKnown(typed)
    }

    private fun chooseCorrection(word: TypedWord, typedIsValid: Boolean, full: List<Candidate>, level: AutocorrectLevel): String? {
        val typed = word.text
        val lower = word.lower
        if (lower in ignored) return null
        if (language == "en") EN_SHORTCUTS[lower]?.let { return applyCase(typed, it) }

        // Exact word with mandatory capitals ("i" -> "I", "i'm" -> "I'm").
        val exact = main.find(lower)
        if (exact >= 0) {
            val display = main.words[exact]
            if (display != lower && typed == lower) return display
        }

        if (typedIsValid) {
            val typedFreq = main.freqOf(lower).coerceAtLeast(user.freqOf(lower))
            if (learning.countOf(typed) > 0 || learning.countOf(lower) > 0) return null
            // "dont" -> "don't", "cafe" -> "café", but leave "its"/"it's" alone.
            val variant = full.firstOrNull { it.cost <= 0.12f && !it.word.equals(lower, true) }
            if (variant != null && main.freqOf(variant.word) >= typedFreq + 1.2f) return applyCase(typed, variant.word)
            // A rare dictionary word right next to a very common one is usually a typo ("rhe").
            if (level != AutocorrectLevel.MILD && typedFreq in 0f..2.6f) {
                val near = full.firstOrNull { !it.word.equals(lower, true) }
                if (near != null && near.cost <= 0.6f && main.freqOf(near.word) >= typedFreq + 3f) return applyCase(typed, near.word)
            }
            return null
        }
        if (word.length < 2) return null
        val best = full.firstOrNull() ?: return null
        val freq = main.freqOf(best.word).coerceAtLeast(user.freqOf(best.word))
        if (best.cost > maxCorrectionCost(word.length, level)) return null
        if (freq < MIN_CORRECTION_FREQ) return null
        // If the top two are nearly tied and both are plausible, still pick the best: that's what
        // the spatial model is for. But never trade a long word for a much shorter one.
        if (best.word.length < word.length - 2) return null
        return applyCase(typed, best.word)
    }

    private fun maxCorrectionCost(len: Int, level: AutocorrectLevel): Float {
        val base = when {
            len <= 2 -> 0.55f
            len <= 4 -> 1.0f
            len <= 7 -> 1.6f
            else -> 2.1f
        }
        return when (level) {
            AutocorrectLevel.OFF -> -1f
            AutocorrectLevel.MILD -> base * 0.65f
            AutocorrectLevel.NORMAL -> base
            AutocorrectLevel.AGGRESSIVE -> base + 0.4f
        }
    }

    private fun personalBonus(word: String): Float {
        val c = learning.countOf(word)
        return if (c <= 0) 0f else min(1.2f, 0.35f * log2(1f + c))
    }

    private fun contextBonus(prev: String?, word: String): Float {
        if (prev == null) return 0f
        val c = learning.bigramCount(prev, word)
        val seed = if (language == "en" && EN_FOLLOWERS[prev.lowercase()]?.contains(word.lowercase()) == true) 0.8f else 0f
        return min(2.5f, seed + if (c > 0) 0.7f * log2(1f + c) else 0f)
    }

    // ------------------------------------------------------------------ predictions

    /** Next-word predictions after [prev] (null = start of text). */
    @Synchronized
    fun predict(prev: String?, limit: Int = 5, capitalize: Boolean = false): List<String> {
        val out = LinkedHashMap<String, Float>()
        if (prev != null) {
            for ((w, c) in learning.followers(prev)) out[w] = 2f + log2(1f + c)
            if (language == "en") EN_FOLLOWERS[prev.lowercase()]?.forEachIndexed { i, w ->
                val f = main.freqOf(w).coerceAtLeast(0f)
                out.merge(w, 1f + f / 4f - i * 0.05f) { a, b -> a + b }
            }
        } else {
            starters().forEachIndexed { i, w -> out[w] = 1f - i * 0.01f }
        }
        if (out.size < limit && prev != null) {
            // Fallback: the most common words in the language.
            for (w in topWords) { if (out.size >= limit) break; out.putIfAbsent(w, 0f) }
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }
            .map { if (capitalize) capitalizeFirst(it) else it }
            .distinct()
            .take(limit)
    }

    private fun starters(): List<String> = if (language == "en") EN_STARTERS else topWords.take(6).map(::capitalizeFirst)

    private val topWords: List<String> by lazy {
        val ids = (0 until main.wordCount).sortedByDescending { main.freqs[it] }.take(12)
        ids.map { main.words[it] }
    }

    // ------------------------------------------------------------------ learning

    /** Called for every word committed in a normal (non-password) field. */
    @Synchronized
    fun learn(word: String, prev: String?) {
        if (word.isEmpty() || !word.any { it.isLetter() }) return
        val wasLearned = isLearned(word, learning.countOf(word))
        learning.addWord(word)
        if (prev != null) learning.addBigram(prev, word)
        if (!wasLearned && isLearned(word, learning.countOf(word))) user.put(word, userFreq(learning.countOf(word)))
    }

    /** The user undid an autocorrection: respect their word from now on. */
    @Synchronized
    fun rejectCorrection(original: String) {
        ignored += original.lowercase()
        learning.addWord(original, 2)
        if (main.find(original) < 0) user.put(original, userFreq(learning.countOf(original)))
    }

    @Synchronized
    fun forget(word: String) {
        learning.removeWord(word)
        ignored -= word.lowercase()
        rebuildUserLexicon()
    }

    companion object {
        const val COST_WEIGHT = 2.5f
        const val MIN_CORRECTION_FREQ = 2.4f

        /** Parse "word<TAB>zipf*100" lines. */
        fun loadDictionary(lines: Sequence<String>): Trie {
            val t = Trie()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val f = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                t.put(line.substring(0, tab), f / 100f)
            }
            return t
        }

        fun applyCase(typed: String, candidate: String): String {
            if (typed.isEmpty()) return candidate
            val letters = typed.filter { it.isLetter() }
            val allCaps = letters.length > 1 && letters.all { it.isUpperCase() }
            return when {
                allCaps -> candidate.uppercase()
                candidate.any { it.isUpperCase() } -> candidate
                typed[0].isUpperCase() -> capitalizeFirst(candidate)
                else -> candidate
            }
        }

        fun capitalizeFirst(s: String) = if (s.isEmpty()) s else s[0].titlecase() + s.substring(1)

        private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()

        private val EN_SHORTCUTS = mapOf("alot" to "a lot", "i" to "I")

        private val EN_STARTERS = listOf("I", "The", "Thanks", "Hi", "Yes", "Ok", "It", "We")

        private val EN_FOLLOWERS: Map<String, List<String>> = mapOf(
            "i" to listOf("am", "have", "think", "don't", "will", "was", "can", "love", "just", "need"),
            "i'm" to listOf("not", "so", "going", "sure", "sorry", "on", "at", "in"),
            "i'll" to listOf("be", "have", "send", "call", "let", "see"),
            "you" to listOf("are", "can", "know", "have", "want", "too", "guys"),
            "the" to listOf("best", "same", "first", "other", "way", "time", "end"),
            "thank" to listOf("you"),
            "thanks" to listOf("for", "so", "again", "a"),
            "how" to listOf("are", "about", "much", "do", "is", "was"),
            "what" to listOf("is", "are", "do", "about", "time", "happened"),
            "it" to listOf("is", "was", "will", "would", "was"),
            "it's" to listOf("a", "not", "so", "the", "been"),
            "is" to listOf("the", "a", "it", "that", "not", "going"),
            "are" to listOf("you", "we", "the", "they", "not"),
            "we" to listOf("are", "can", "have", "will", "need", "should"),
            "to" to listOf("the", "be", "get", "do", "go", "see", "make"),
            "of" to listOf("the", "course", "my", "a", "this"),
            "in" to listOf("the", "a", "my", "this", "an"),
            "on" to listOf("the", "my", "it", "a", "your"),
            "my" to listOf("way", "phone", "friend", "mom", "life", "god"),
            "for" to listOf("the", "a", "you", "me", "your", "sure"),
            "and" to listOf("I", "the", "then", "a", "it"),
            "a" to listOf("lot", "few", "little", "good", "great", "bit"),
            "good" to listOf("morning", "night", "luck", "job", "to", "idea"),
            "see" to listOf("you", "the", "it", "if"),
            "let" to listOf("me", "us", "you"),
            "let's" to listOf("go", "do", "see", "get", "meet"),
            "can" to listOf("you", "I", "we", "be", "do"),
            "will" to listOf("be", "you", "do", "have", "send"),
            "have" to listOf("a", "to", "you", "been", "the", "fun"),
            "going" to listOf("to", "on", "out"),
            "want" to listOf("to", "a", "you", "it"),
            "need" to listOf("to", "a", "you", "help", "it"),
            "please" to listOf("let", "send", "call", "find", "check"),
            "do" to listOf("you", "not", "it", "we", "that"),
            "don't" to listOf("know", "have", "want", "think", "worry"),
            "call" to listOf("me", "you", "it"),
            "talk" to listOf("to", "about", "soon", "later"),
            "love" to listOf("you", "it", "the", "this"),
            "miss" to listOf("you"),
            "happy" to listOf("birthday", "to", "new", "holidays"),
            "no" to listOf("problem", "worries", "idea", "one", "way"),
            "sounds" to listOf("good", "great", "like"),
            "at" to listOf("the", "home", "work", "least", "all"),
            "so" to listOf("much", "I", "the", "good", "far"),
            "that" to listOf("is", "was", "I", "the", "would"),
            "this" to listOf("is", "was", "weekend", "morning", "one"),
            "be" to listOf("there", "a", "the", "able"),
            "how's" to listOf("it", "your", "the"),
        )
    }
}
