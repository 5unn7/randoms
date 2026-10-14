package app.foldboard

import android.content.Context
import app.foldboard.core.text.LearningStore
import app.foldboard.core.text.TextEngine
import java.io.File
import java.util.concurrent.Executors

/**
 * Loads dictionaries off the main thread and keeps one [TextEngine] per language for the life of
 * the process, so reopening the keyboard is instant. Learned data lives in the app's private files.
 */
object Engines {
    private val io = Executors.newSingleThreadExecutor()
    private val engines = HashMap<String, TextEngine>()
    private val loading = HashSet<String>()

    @Synchronized
    fun get(lang: String): TextEngine? = engines[lang]

    fun load(context: Context, lang: String, onReady: (TextEngine) -> Unit) {
        val app = context.applicationContext
        synchronized(this) {
            engines[lang]?.let { onReady(it); return }
            if (!loading.add(lang)) return
        }
        io.execute {
            val trie = app.assets.open("dict/$lang.txt").bufferedReader().useLines { TextEngine.loadDictionary(it) }
            val store = LearningStore()
            val f = learnFile(app, lang)
            if (f.exists()) runCatching { store.load(f.readText()) }
            val engine = TextEngine(lang, trie, store)
            synchronized(this) { engines[lang] = engine; loading -= lang }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(engine) }
        }
    }

    fun save(context: Context) {
        val app = context.applicationContext
        val snapshot = synchronized(this) { engines.values.toList() }
        io.execute {
            for (e in snapshot) {
                val text = synchronized(e) { if (e.learning.dirty) e.learning.serialize() else null } ?: continue
                runCatching { learnFile(app, e.language).writeText(text) }
            }
        }
    }

    fun clearLearning(context: Context) {
        val app = context.applicationContext
        val snapshot = synchronized(this) { engines.values.toList() }
        for (e in snapshot) synchronized(e) { e.learning.clear(); e.rebuildUserLexicon() }
        io.execute { app.filesDir.listFiles()?.filter { it.name.startsWith("learned_") }?.forEach { it.delete() } }
    }

    private fun learnFile(context: Context, lang: String) = File(context.filesDir, "learned_$lang.txt")
}
