package app.foldboard.core.text

/**
 * Turns raw speech-recognizer output into text ready to insert: spoken punctuation commands,
 * sentence capitalization and spacing relative to what is already before the cursor.
 */
object VoiceText {
    private val EN_COMMANDS = listOf(
        "new paragraph" to "\n\n", "new line" to "\n", "next line" to "\n",
        "question mark" to "?", "exclamation mark" to "!", "exclamation point" to "!",
        "full stop" to ".", "period" to ".", "comma" to ",", "colon" to ":", "semicolon" to ";",
        "open bracket" to "(", "close bracket" to ")", "dash" to " -", "smiley face" to ":)",
    )
    private val NO_SPACE_BEFORE = setOf('.', ',', '!', '?', ':', ';', ')', '\n')
    private val SENTENCE_END = setOf('.', '!', '?', '\n')

    fun process(raw: String, language: String, commands: Boolean): String {
        var t = raw.trim()
        if (commands && language == "en") {
            for ((spoken, sym) in EN_COMMANDS) {
                t = t.replace(Regex("(?i)\\s*\\b$spoken\\b"), sym)
            }
            t = t.replace(Regex(" *\n *"), "\n")
        }
        // Capitalize after sentence ends produced by commands.
        val sb = StringBuilder(t)
        var cap = false
        for (i in sb.indices) {
            val c = sb[i]
            if (c in SENTENCE_END) cap = true
            else if (c.isLetter()) { if (cap) sb.setCharAt(i, c.uppercaseChar()); cap = false }
        }
        // Space after punctuation that is followed directly by a word.
        return sb.toString().replace(Regex("([.,!?;:])(?=[^\\s.,!?;:)])"), "$1 ")
    }

    /** Prefix (a space or nothing) and casing so the text joins naturally with [before]. */
    fun join(before: String, text: String, autoCaps: Boolean): String {
        if (text.isEmpty()) return text
        val startOfSentence = before.isBlank() || before.trimEnd().lastOrNull()?.let { it in SENTENCE_END } == true
        var t = if (autoCaps && startOfSentence) text[0].uppercaseChar() + text.substring(1) else text
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && t[0] !in NO_SPACE_BEFORE
        if (needsSpace) t = " $t"
        return t
    }
}
