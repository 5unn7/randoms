package app.foldboard

import org.json.JSONArray
import org.json.JSONObject

/** Recent clipboard entries (newest first) with pinning, persisted in preferences. */
class ClipboardHistory(private val prefs: Prefs) {
    data class Entry(val text: String, val time: Long, val pinned: Boolean)

    var entries: List<Entry> = load()
        private set

    fun add(text: String) {
        if (text.isBlank() || text.length > 5000) return
        val existing = entries.firstOrNull { it.text == text }
        val rest = entries.filter { it.text != text }
        val pinned = existing?.pinned ?: false
        entries = (listOf(Entry(text, System.currentTimeMillis(), pinned)) + rest)
            .let { all -> all.filter { it.pinned } + all.filter { !it.pinned }.take(MAX) }
            .sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.time })
        save()
    }

    fun togglePin(text: String) {
        entries = entries.map { if (it.text == text) it.copy(pinned = !it.pinned) else it }
            .sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.time })
        save()
    }

    fun remove(text: String) { entries = entries.filter { it.text != text }; save() }

    fun clearUnpinned() { entries = entries.filter { it.pinned }; save() }

    /** Most recent clip if it was copied within [windowMs]: offered as a one-tap paste suggestion. */
    fun fresh(windowMs: Long = 60_000): Entry? =
        entries.maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time < windowMs }

    private fun load(): List<Entry> = try {
        val arr = JSONArray(prefs.sp.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Entry(it.getString("t"), it.getLong("ts"), it.optBoolean("p")) }
    } catch (e: Exception) { emptyList() }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("t", e.text).put("ts", e.time).put("p", e.pinned))
        prefs.sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "clip_history"
        private const val MAX = 30
    }
}
