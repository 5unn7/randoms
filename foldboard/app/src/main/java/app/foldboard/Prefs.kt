package app.foldboard

import android.content.Context
import android.content.SharedPreferences
import app.foldboard.core.text.AutocorrectLevel

/** All user settings, backed by SharedPreferences. Keys are shared with [SettingsActivity]. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun i(key: String, def: Int) = sp.getInt(key, def)

    // Layout
    val alphabet get() = s(ALPHABET, "qwerty")
    val keyStyle get() = s(KEY_STYLE, "hex")             // hex | classic
    val splitMode get() = s(SPLIT_MODE, "auto")          // auto | always | never
    val centerPanel get() = b(CENTER_PANEL, true)
    val oneHanded get() = s(ONE_HANDED, "off")           // off | left | right
    val micKey get() = b(MIC_KEY, true)

    // Size
    val heightFolded get() = i(HEIGHT_FOLDED, 100)
    val heightUnfolded get() = i(HEIGHT_UNFOLDED, 100)
    val splitGap get() = i(SPLIT_GAP, 30)                // tenths of a key width
    val keyGap get() = i(KEY_GAP, 3)                     // dp
    val labelScale get() = i(LABEL_SCALE, 100)           // percent
    val bottomPadding get() = i(BOTTOM_PADDING, 4)       // dp

    // Typing
    val language get() = s(LANGUAGE, "en")
    val autocorrect: AutocorrectLevel get() = when (s(AUTOCORRECT, "normal")) {
        "off" -> AutocorrectLevel.OFF
        "mild" -> AutocorrectLevel.MILD
        "aggressive" -> AutocorrectLevel.AGGRESSIVE
        else -> AutocorrectLevel.NORMAL
    }
    val suggestions get() = b(SUGGESTIONS, true)
    val nextWord get() = b(NEXT_WORD, true)
    val autoCaps get() = b(AUTO_CAPS, true)
    val doubleSpacePeriod get() = b(DOUBLE_SPACE, true)
    val autoSpace get() = b(AUTO_SPACE, true)
    val learnWords get() = b(LEARN, true)

    // Gestures
    val swipeUpCaps get() = b(SWIPE_UP, true)
    val swipeDownSymbol get() = b(SWIPE_DOWN, true)
    val swipeLeftDelete get() = b(SWIPE_LEFT, true)
    val swipeRightRestore get() = b(SWIPE_RIGHT, true)
    val spaceCursor get() = b(SPACE_CURSOR, true)
    val glide get() = b(GLIDE, false)
    val longPressMs get() = i(LONG_PRESS, 320)

    // Feedback
    val haptics get() = i(HAPTICS, 35)                   // 0..100
    val sound get() = i(SOUND, 0)                        // 0..100
    val keyPopup get() = b(KEY_POPUP, true)

    // Voice
    val voiceContinuous get() = b(VOICE_CONTINUOUS, true)
    val voiceCommands get() = b(VOICE_COMMANDS, true)
    val voiceOffline get() = b(VOICE_OFFLINE, true)

    // Look
    val theme get() = s(THEME, "system")                 // system | dark | amoled | light
    val accent get() = s(ACCENT, "blue")
    val showSecondary get() = b(SHOW_SECONDARY, true)

    // Clipboard
    val clipboardHistory get() = b(CLIPBOARD, true)

    companion object {
        const val NAME = "foldboard"
        const val ALPHABET = "alphabet"
        const val KEY_STYLE = "key_style"
        const val SPLIT_MODE = "split_mode"
        const val CENTER_PANEL = "center_panel"
        const val ONE_HANDED = "one_handed"
        const val MIC_KEY = "mic_key"
        const val HEIGHT_FOLDED = "height_folded"
        const val HEIGHT_UNFOLDED = "height_unfolded"
        const val SPLIT_GAP = "split_gap"
        const val KEY_GAP = "key_gap"
        const val LABEL_SCALE = "label_scale"
        const val BOTTOM_PADDING = "bottom_padding"
        const val LANGUAGE = "language"
        const val AUTOCORRECT = "autocorrect"
        const val SUGGESTIONS = "suggestions"
        const val NEXT_WORD = "next_word"
        const val AUTO_CAPS = "auto_caps"
        const val DOUBLE_SPACE = "double_space"
        const val AUTO_SPACE = "auto_space"
        const val LEARN = "learn"
        const val SWIPE_UP = "swipe_up"
        const val SWIPE_DOWN = "swipe_down"
        const val SWIPE_LEFT = "swipe_left"
        const val SWIPE_RIGHT = "swipe_right"
        const val SPACE_CURSOR = "space_cursor"
        const val GLIDE = "glide"
        const val LONG_PRESS = "long_press"
        const val HAPTICS = "haptics"
        const val SOUND = "sound"
        const val KEY_POPUP = "key_popup"
        const val VOICE_CONTINUOUS = "voice_continuous"
        const val VOICE_COMMANDS = "voice_commands"
        const val VOICE_OFFLINE = "voice_offline"
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val SHOW_SECONDARY = "show_secondary"
        const val CLIPBOARD = "clipboard"

        val LANGUAGES = listOf(
            "en" to "English", "es" to "Español", "fr" to "Français",
            "de" to "Deutsch", "it" to "Italiano", "pt" to "Português",
        )
        val ACCENTS = listOf(
            "blue" to 0xFF4BA3F0.toInt(), "teal" to 0xFF2BB5A3.toInt(), "orange" to 0xFFF08A3C.toInt(),
            "pink" to 0xFFE8608F.toInt(), "purple" to 0xFF9B7BF0.toInt(), "green" to 0xFF5DBE6A.toInt(),
        )
    }
}
