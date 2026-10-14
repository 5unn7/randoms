package app.foldboard

import android.content.Context
import android.content.res.Configuration

data class KbTheme(
    val background: Int,
    val key: Int,
    val keyFunction: Int,
    val keySpace: Int,
    val keyPressed: Int,
    val text: Int,
    val textDim: Int,
    val accent: Int,
    val onAccent: Int,
    val strip: Int,
    val popup: Int,
    val divider: Int,
    val dark: Boolean,
    val shadow: Int = if (dark) 0x66000000 else 0x2E000000,
) {
    /** Accent darkened for the pressed enter key. */
    val accentPressed: Int get() {
        val r = ((accent shr 16) and 0xFF) * 3 / 4; val g = ((accent shr 8) and 0xFF) * 3 / 4; val b = (accent and 0xFF) * 3 / 4
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        fun resolve(context: Context, prefs: Prefs): KbTheme {
            val accent = Prefs.ACCENTS.firstOrNull { it.first == prefs.accent }?.second ?: Prefs.ACCENTS[0].second
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return when (prefs.theme) {
                "light" -> light(accent)
                "amoled" -> amoled(accent)
                "dark" -> dark(accent)
                else -> if (systemDark) dark(accent) else light(accent)
            }
        }

        fun dark(accent: Int) = KbTheme(
            background = 0xFF0E0F12.toInt(), key = 0xFF2F3036.toInt(), keyFunction = 0xFF232429.toInt(),
            keySpace = 0xFF4A4C55.toInt(), keyPressed = 0xFF5C5F6A.toInt(), text = 0xFFF2F2F4.toInt(),
            textDim = 0xFF9A9AA2.toInt(), accent = accent, onAccent = 0xFFFFFFFF.toInt(),
            strip = 0xFF0E0F12.toInt(), popup = 0xFF464851.toInt(), divider = 0xFF2A2B31.toInt(), dark = true,
        )

        fun amoled(accent: Int) = KbTheme(
            background = 0xFF000000.toInt(), key = 0xFF1E1E21.toInt(), keyFunction = 0xFF141416.toInt(),
            keySpace = 0xFF3C3C42.toInt(), keyPressed = 0xFF55555C.toInt(), text = 0xFFF2F2F4.toInt(),
            textDim = 0xFF8A8A92.toInt(), accent = accent, onAccent = 0xFFFFFFFF.toInt(),
            strip = 0xFF000000.toInt(), popup = 0xFF35353A.toInt(), divider = 0xFF1C1C1F.toInt(), dark = true,
        )

        fun light(accent: Int) = KbTheme(
            background = 0xFFE4E6EB.toInt(), key = 0xFFFFFFFF.toInt(), keyFunction = 0xFFCDD0D8.toInt(),
            keySpace = 0xFFB4B8C2.toInt(), keyPressed = 0xFFA6AAB4.toInt(), text = 0xFF15161A.toInt(),
            textDim = 0xFF6B6E78.toInt(), accent = accent, onAccent = 0xFFFFFFFF.toInt(),
            strip = 0xFFECEEF2.toInt(), popup = 0xFFFFFFFF.toInt(), divider = 0xFFD2D5DC.toInt(), dark = false,
        )
    }
}
