package app.foldboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictation through the system speech recognizer.
 *
 * Android recognizers end a session after a short pause. To dictate continuously we restart the
 * recognizer after every result (and after "no match"/timeout/busy/client errors), keep any text
 * that only arrived as a partial result, and fall back from the on-device recognizer to the
 * default one if it refuses. Listening stops when the user taps stop or after [silenceLimitMs]
 * without speech.
 */
class VoiceInput(private val context: Context, private val callback: Callback) : RecognitionListener {

    interface Callback {
        fun onVoicePartial(text: String)
        fun onVoiceFinal(text: String)
        fun onVoiceState(listening: Boolean, error: String?)
        fun onVoiceLevel(level: Float) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var language = "en-US"
    private var continuous = true
    private var preferOffline = true
    private var silenceLimitMs = 20_000L
    private var lastSpeech = 0L
    private var lastPartial = ""
    private var consecutiveErrors = 0
    var listening = false
        private set

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, continuous: Boolean, preferOffline: Boolean, silenceLimitSec: Int) {
        language = languageTag
        this.continuous = continuous
        this.preferOffline = preferOffline
        silenceLimitMs = silenceLimitSec * 1000L
        lastSpeech = SystemClock.uptimeMillis()
        lastPartial = ""
        consecutiveErrors = 0
        listening = true
        callback.onVoiceState(true, null)
        listen()
    }

    private fun create(): SpeechRecognizer {
        usingOnDevice = preferOffline && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = if (usingOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(this)
        return r
    }

    private fun recreate(forceDefault: Boolean) {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (forceDefault) preferOffline = false
    }

    private fun listen() {
        if (!listening) return
        val r = recognizer ?: create().also { recognizer = it }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Ask for longer pauses before the recognizer decides we're done (not all honor it).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        lastPartial = ""
        try {
            r.startListening(i)
        } catch (e: Exception) {
            recreate(forceDefault = usingOnDevice)
            restartSoon(400)
        }
    }

    private fun restartSoon(delayMs: Long = 120) {
        main.postDelayed({ if (listening) listen() }, delayMs)
    }

    fun stop() {
        if (!listening) return
        listening = false
        main.removeCallbacksAndMessages(null)
        flushPartial()
        try { recognizer?.cancel() } catch (_: Exception) {}
        callback.onVoiceState(false, null)
    }

    fun destroy() {
        listening = false
        main.removeCallbacksAndMessages(null)
        recreate(false)
    }

    private fun finish(error: String?) {
        listening = false
        main.removeCallbacksAndMessages(null)
        flushPartial()
        callback.onVoiceState(false, error)
    }

    /** Some recognizers only deliver text as partials before an error: keep it. */
    private fun flushPartial() {
        val p = lastPartial
        lastPartial = ""
        if (p.isNotBlank()) callback.onVoiceFinal(p)
    }

    private fun silentTooLong() = SystemClock.uptimeMillis() - lastSpeech > silenceLimitMs

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (t.isNotBlank()) {
            lastPartial = t
            lastSpeech = SystemClock.uptimeMillis()
            consecutiveErrors = 0
            callback.onVoicePartial(t)
        }
    }

    override fun onResults(results: Bundle?) {
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (t.isNotBlank()) {
            lastPartial = ""
            lastSpeech = SystemClock.uptimeMillis()
            callback.onVoiceFinal(t)
        } else flushPartial()
        consecutiveErrors = 0
        if (listening && continuous && !silentTooLong()) restartSoon() else finish(null)
    }

    override fun onError(error: Int) {
        flushPartial()
        if (!listening) return
        consecutiveErrors++
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (continuous && !silentTooLong()) restartSoon() else finish(null)
            SpeechRecognizer.ERROR_CLIENT ->
                if (consecutiveErrors <= 4) restartSoon(250) else finish("Voice input stopped")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                recreate(false)
                if (consecutiveErrors <= 4) restartSoon(350) else finish("Voice recognizer is busy")
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish("Allow microphone access in FoldBoard settings")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                if (usingOnDevice || consecutiveErrors > 2) finish("Voice needs a connection or an offline language pack")
                else restartSoon(500)
            else -> {
                // Language unavailable on-device, server disconnected, too many requests, ...
                if (usingOnDevice) { recreate(forceDefault = true); restartSoon(200) }
                else if (consecutiveErrors <= 2) { recreate(false); restartSoon(400) }
                else finish("Voice input error ($error)")
            }
        }
    }

    override fun onBeginningOfSpeech() {
        lastSpeech = SystemClock.uptimeMillis()
    }

    override fun onRmsChanged(rmsdB: Float) {
        callback.onVoiceLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
