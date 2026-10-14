package app.foldboard

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Key-press haptics and click sound with adjustable strength. */
class Feedback(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var hapticStrength = 35 // 0..100
    var soundVolume = 0     // 0..100

    fun keyPress(special: Boolean = false) {
        if (hapticStrength > 0) vibrate(special)
        if (soundVolume > 0) {
            val fx = if (special) AudioManager.FX_KEYPRESS_RETURN else AudioManager.FX_KEYPRESS_STANDARD
            audio?.playSoundEffect(fx, soundVolume / 100f)
        }
    }

    private fun vibrate(special: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val ms = (6 + hapticStrength / 8).toLong() + if (special) 4 else 0
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(ms, (40 + hapticStrength * 2).coerceAtMost(255))
        } else VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        v.vibrate(effect)
    }
}
