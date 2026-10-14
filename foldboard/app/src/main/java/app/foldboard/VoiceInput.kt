package app.foldboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictation through the system speech recognizer (on-device when available). In continuous mode
 * it keeps listening across pauses until stopped, streaming partial text as it goes.
 */
class VoiceInput(private val context: Context, private val callback: Callback) : RecognitionListener {

    interface Callback {
        fun onVoicePartial(text: String)
        fun onVoiceFinal(text: String)
        fun onVoiceState(listening: Boolean, error: String?)
    }

    private var recognizer: SpeechRecognizer? = null
    private var language = "en-US"
    private var continuous = true
    private var preferOffline = true
    private var emptyRounds = 0
    var listening = false
        private set

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, continuous: Boolean, preferOffline: Boolean) {
        language = languageTag
        this.continuous = continuous
        this.preferOffline = preferOffline
        emptyRounds = 0
        if (recognizer == null) recognizer = create()
        listening = true
        callback.onVoiceState(true, null)
        listen()
    }

    private fun create(): SpeechRecognizer {
        val r = if (preferOffline && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(this)
        return r
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            finish("Voice input failed to start")
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        callback.onVoiceState(false, null)
    }

    fun destroy() {
        listening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun finish(error: String?) {
        listening = false
        callback.onVoiceState(false, error)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (t.isNotBlank()) callback.onVoicePartial(t)
    }

    override fun onResults(results: Bundle?) {
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (t.isNotBlank()) { emptyRounds = 0; callback.onVoiceFinal(t) } else emptyRounds++
        if (listening && continuous && emptyRounds < 2) listen() else finish(null)
    }

    override fun onError(error: Int) {
        callback.onVoicePartial("")
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                emptyRounds++
                if (listening && continuous && emptyRounds < 2) listen() else finish(null)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish("Allow microphone access in FoldBoard settings")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> finish("Voice needs a connection or an offline language pack")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> finish("Voice recognizer is busy")
            SpeechRecognizer.ERROR_CLIENT -> if (listening) finish(null)
            else -> finish("Voice input error ($error)")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
