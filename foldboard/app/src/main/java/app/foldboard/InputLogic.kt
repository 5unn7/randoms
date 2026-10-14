package app.foldboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import app.foldboard.core.text.TextEngine
import app.foldboard.core.text.TypedWord
import app.foldboard.core.text.VoiceText
import java.util.concurrent.Executors

/**
 * Everything that turns key events into edits: the composing word, autocorrect on space,
 * undo-correction on backspace, smart punctuation spacing, delete/restore word swipes,
 * auto-capitalization and the suggestion strip contents.
 */
class InputLogic(private val service: FoldBoardService) {

    private val ic: InputConnection? get() = service.currentInputConnection
    private val prefs get() = service.prefs
    var engine: TextEngine? = null

    // Field capabilities
    private var info: EditorInfo? = null
    private var inputType = 0
    private var allowComposing = true
    private var allowAutocorrect = true
    private var allowLearn = true
    private var allowAutoCaps = true

    // Current word
    private val composing = StringBuilder()
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()

    // Undo / smart behaviour state
    private var lastCorrection: Pair<String, String>? = null // original -> corrected
    private val deleted = ArrayDeque<String>()
    private var autoSpaced = false
    private var lastSpaceTime = 0L
    private var shiftTapTime = 0L
    private var shiftAuto = false
    private var justBackspaced = false
    private var lastGlide: String? = null
    private var glideAlternatives: List<String> = emptyList()
    private var voiceLen = 0

    var shift = ShiftState.OFF
        private set(value) { field = value; service.onShiftChanged(value) }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var suggestSeq = 0

    // ------------------------------------------------------------------ lifecycle

    fun startInput(editorInfo: EditorInfo) {
        info = editorInfo
        inputType = editorInfo.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val urlOrEmail = cls == InputType.TYPE_CLASS_TEXT && variation in URL_EMAIL_VARIATIONS
        val noSuggestions = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        allowComposing = cls == InputType.TYPE_CLASS_TEXT && !password && !noSuggestions && prefs.suggestions
        allowAutocorrect = allowComposing && !urlOrEmail
        allowLearn = !password && prefs.learnWords &&
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        allowAutoCaps = prefs.autoCaps && cls == InputType.TYPE_CLASS_TEXT && !urlOrEmail && !password
        resetWord()
        lastCorrection = null
        deleted.clear()
        autoSpaced = false
        lastGlide = null
        voiceLen = 0
        shiftAuto = false
        shift = ShiftState.OFF
        updateShiftAuto()
        refreshSuggestions()
    }

    fun finishInput() {
        if (composing.isNotEmpty()) ic?.finishComposingText()
        resetWord()
    }

    private fun resetWord() {
        composing.clear(); xs.clear(); ys.clear()
    }

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (voiceLen > 0) return
        if (composing.isNotEmpty() && (candidatesEnd == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            // The cursor moved away from the word we were building (tap elsewhere, app edit).
            ic?.finishComposingText()
            resetWord()
            lastCorrection = null
            autoSpaced = false
            lastGlide = null
        }
        if (composing.isEmpty()) {
            if (justBackspaced) {
                justBackspaced = false
                if (newSelStart == newSelEnd) resumeWordAt(newSelStart)
            }
            updateShiftAuto()
            refreshSuggestions()
        }
    }

    /** After backspacing into a word, pick it up again so suggestions and corrections apply. */
    private fun resumeWordAt(cursor: Int) {
        if (!allowComposing) return
        val c = ic ?: return
        val before = c.getTextBeforeCursor(48, 0) ?: return
        val after = c.getTextAfterCursor(1, 0) ?: ""
        if (after.isNotEmpty() && isWordChar(after[0])) return
        var n = 0
        while (n < before.length && isWordChar(before[before.length - 1 - n])) n++
        if (n == 0 || n > 40 || cursor - n < 0) return
        val word = before.substring(before.length - n)
        if (word.startsWith("'")) return
        c.setComposingRegion(cursor - n, cursor)
        composing.append(word)
        repeat(n) { xs += Float.NaN; ys += Float.NaN }
    }

    // ------------------------------------------------------------------ typing

    fun onText(text: String, x: Float, y: Float, forceUpper: Boolean) {
        val c = ic ?: return
        lastGlide = null
        val letter = text.length == 1 && text[0].isLetter()
        val t = if (letter && (forceUpper || shift != ShiftState.OFF)) text.uppercase() else text
        val ch = t[0]
        if (allowComposing && t.length == 1 && (ch.isLetter() || (ch == '\'' && composing.isNotEmpty()))) {
            composing.append(t); xs += x; ys += y
            c.setComposingText(composing, 1)
            autoSpaced = false
            lastCorrection = null
        } else if (!allowComposing) {
            c.commitText(t, 1)
        } else {
            val sentencePunct = t.length == 1 && ch in ".,!?;:"
            if (composing.isNotEmpty()) commitComposing(applyCorrection = sentencePunct)
            if (sentencePunct && autoSpaced && c.getTextBeforeCursor(1, 0)?.toString() == " ") {
                // "word |" + "." -> "word. |"
                c.beginBatchEdit()
                c.deleteSurroundingText(1, 0)
                c.commitText("$t ", 1)
                c.endBatchEdit()
                autoSpaced = true
            } else {
                c.commitText(t, 1)
                autoSpaced = false
            }
        }
        if (shift == ShiftState.ON && letter) { shift = ShiftState.OFF; shiftAuto = false }
        if (composing.isEmpty()) updateShiftAuto()
        refreshSuggestions()
    }

    private fun commitComposing(applyCorrection: Boolean) {
        val c = ic ?: return
        if (composing.isEmpty()) return
        val typed = composing.toString()
        val prev = previousWord()
        var out = typed
        val e = engine
        if (applyCorrection && allowAutocorrect && e != null) {
            val r = e.suggest(typedWord(), prev, 3, prefs.autocorrect)
            if (r.corrected) out = r.primary
        }
        c.commitText(out, 1)
        lastCorrection = if (out != typed) typed to out else null
        if (allowLearn) e?.learn(out, prev)
        resetWord()
    }

    private fun typedWord() = TypedWord(composing.toString(), xs.toFloatArray(), ys.toFloatArray())

    fun onSpace() {
        val c = ic ?: return
        lastGlide = null
        val now = SystemClock.uptimeMillis()
        if (composing.isNotEmpty()) {
            commitComposing(applyCorrection = true)
            c.commitText(" ", 1)
            autoSpaced = true
        } else {
            val before = c.getTextBeforeCursor(2, 0)
            if (prefs.doubleSpacePeriod && allowComposing && now - lastSpaceTime < 700 && before != null && before.length == 2 &&
                before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in "\"')")
            ) {
                c.beginBatchEdit()
                c.deleteSurroundingText(1, 0)
                c.commitText(". ", 1)
                c.endBatchEdit()
                autoSpaced = true
            } else {
                c.commitText(" ", 1)
                autoSpaced = false
            }
        }
        lastSpaceTime = now
        updateShiftAuto()
        refreshSuggestions()
    }

    fun onBackspace() {
        val c = ic ?: return
        lastGlide = null
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1)
            if (composing.isEmpty()) c.commitText("", 1) else c.setComposingText(composing, 1)
            if (composing.isEmpty()) updateShiftAuto()
            refreshSuggestions()
            return
        }
        lastCorrection?.let { (original, corrected) ->
            lastCorrection = null
            val before = c.getTextBeforeCursor(corrected.length + 1, 0)?.toString()
            val len = when {
                before == null -> 0
                before.endsWith("$corrected ") -> corrected.length + 1
                before.endsWith(corrected) -> corrected.length
                else -> 0
            }
            if (len > 0) {
                // Undo the autocorrection and keep what the user actually typed.
                c.beginBatchEdit()
                c.deleteSurroundingText(len, 0)
                composing.append(original)
                repeat(original.length) { xs += Float.NaN; ys += Float.NaN }
                c.setComposingText(composing, 1)
                c.endBatchEdit()
                engine?.rejectCorrection(original)
                autoSpaced = false
                refreshSuggestions()
                return
            }
        }
        autoSpaced = false
        val sel = c.getSelectedText(0)
        if (!sel.isNullOrEmpty()) c.commitText("", 1)
        else {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            justBackspaced = allowComposing
        }
    }

    fun onEnter() {
        val c = ic ?: return
        if (composing.isNotEmpty()) commitComposing(applyCorrection = true)
        autoSpaced = false
        lastGlide = null
        val ei = info
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        when {
            !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED -> c.performEditorAction(action)
            multiLine -> c.commitText("\n", 1)
            else -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateShiftAuto()
        refreshSuggestions()
    }

    fun enterLabel(): String? {
        val ei = info ?: return null
        if ((ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> null
        }
    }

    /** Swipe left: delete the previous word (remembered so swipe right can bring it back). */
    fun deleteWord() {
        val c = ic ?: return
        lastGlide = null; lastCorrection = null; autoSpaced = false
        if (composing.isNotEmpty()) {
            pushDeleted(composing.toString())
            resetWord()
            c.commitText("", 1)
        } else {
            val sel = c.getSelectedText(0)
            if (!sel.isNullOrEmpty()) { pushDeleted(sel.toString()); c.commitText("", 1) }
            else {
                val before = c.getTextBeforeCursor(80, 0) ?: return
                if (before.isEmpty()) return
                var i = before.length
                while (i > 0 && before[i - 1].isWhitespace()) i--
                if (i > 0 && isWordChar(before[i - 1])) { while (i > 0 && isWordChar(before[i - 1])) i-- }
                else if (i > 0) i--
                pushDeleted(before.substring(i))
                c.deleteSurroundingText(before.length - i, 0)
            }
        }
        updateShiftAuto()
        refreshSuggestions()
    }

    private fun pushDeleted(s: String) {
        deleted.addLast(s)
        while (deleted.size > 30) deleted.removeFirst()
    }

    /** Swipe right: restore the most recently swiped-away word. */
    fun restoreWord() {
        val c = ic ?: return
        if (composing.isNotEmpty()) commitComposing(false)
        val s = deleted.removeLastOrNull() ?: return
        c.commitText(s, 1)
        updateShiftAuto()
        refreshSuggestions()
    }

    fun moveCursor(delta: Int) {
        val c = ic ?: return
        if (composing.isNotEmpty()) { c.finishComposingText(); resetWord() }
        lastCorrection = null; autoSpaced = false
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(minOf(kotlin.math.abs(delta), 12)) { service.sendDownUpKeyEvents(code) }
    }

    fun onShiftKey() {
        val now = SystemClock.uptimeMillis()
        shiftAuto = false
        shift = when (shift) {
            ShiftState.OFF -> { shiftTapTime = now; ShiftState.ON }
            ShiftState.ON -> if (now - shiftTapTime < 400) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
    }

    fun lockShift() { shiftAuto = false; shift = ShiftState.LOCKED }

    private fun updateShiftAuto() {
        if (!allowAutoCaps || shift == ShiftState.LOCKED || composing.isNotEmpty()) return
        val caps = ic?.getCursorCapsMode(inputType or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) ?: 0
        if (caps != 0) {
            if (shift == ShiftState.OFF) { shiftAuto = true; shift = ShiftState.ON }
        } else if (shift == ShiftState.ON && shiftAuto) {
            shiftAuto = false; shift = ShiftState.OFF
        }
    }

    /** Insert arbitrary text (emoji, clipboard) as its own unit. */
    fun commitRaw(text: String) {
        val c = ic ?: return
        if (composing.isNotEmpty()) commitComposing(false)
        lastGlide = null; lastCorrection = null; autoSpaced = false
        c.commitText(text, 1)
        updateShiftAuto()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ glide

    fun onGlide(xs: FloatArray, ys: FloatArray, count: Int) {
        val e = engine ?: return
        val prev = if (composing.isNotEmpty()) composing.toString() else previousWord()
        val upper = shift
        worker.execute {
            val words = e.decodeGlide(xs, ys, count, prev, 5)
            main.post { commitGlide(words, upper) }
        }
    }

    private fun commitGlide(words: List<String>, upper: ShiftState) {
        val c = ic ?: return
        if (words.isEmpty()) { service.flash("No word matched that swipe"); return }
        if (composing.isNotEmpty()) { commitComposing(true); c.commitText(" ", 1) }
        val before = c.getTextBeforeCursor(1, 0)
        if (!before.isNullOrEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'") c.commitText(" ", 1)
        val adjust = { w: String ->
            when (upper) {
                ShiftState.LOCKED -> w.uppercase()
                ShiftState.ON -> TextEngine.capitalizeFirst(w)
                ShiftState.OFF -> w
            }
        }
        val word = adjust(words[0])
        val prev = previousWord()
        c.commitText("$word ", 1)
        autoSpaced = true
        lastCorrection = null
        lastGlide = word
        glideAlternatives = words.drop(1).map(adjust)
        if (allowLearn) engine?.learn(word, prev)
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; shiftAuto = false }
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ suggestion strip

    fun onPick(item: StripItem) {
        val c = ic ?: return
        if (item.kind == StripItem.Kind.CLIP) { commitRaw(item.text); return }
        val word = item.text
        val glided = lastGlide
        when {
            composing.isNotEmpty() -> {
                val typed = composing.toString()
                val prev = previousWord()
                c.commitText(word, 1)
                resetWord()
                if (item.kind == StripItem.Kind.TYPED) engine?.rejectCorrection(typed)
                else if (allowLearn) engine?.learn(word, prev)
            }
            glided != null && item.kind != StripItem.Kind.PREDICTION -> {
                val before = c.getTextBeforeCursor(glided.length + 1, 0)?.toString()
                if (before == "$glided ") {
                    c.deleteSurroundingText(glided.length + 1, 0)
                    c.commitText(word, 1)
                    if (allowLearn) engine?.learn(word, previousWord())
                } else c.commitText(word, 1)
            }
            else -> {
                val before = c.getTextBeforeCursor(1, 0)
                if (!before.isNullOrEmpty() && !before[0].isWhitespace()) c.commitText(" ", 1)
                val prev = previousWord()
                c.commitText(word, 1)
                if (allowLearn) engine?.learn(word, prev)
            }
        }
        lastGlide = null
        lastCorrection = null
        if (prefs.autoSpace) { c.commitText(" ", 1); autoSpaced = true }
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; shiftAuto = false }
        updateShiftAuto()
        refreshSuggestions()
    }

    fun onPickLong(item: StripItem) {
        if (item.kind == StripItem.Kind.CLIP) return
        engine?.forget(item.text)
        service.flash("Removed “${item.text}” from learned words")
        refreshSuggestions()
    }

    fun refreshSuggestions() {
        if (voiceLen > 0) return
        val e = engine
        if (!prefs.suggestions || !allowComposing || e == null) {
            val clip = service.freshClip()
            service.showSuggestions(if (clip != null && composing.isEmpty()) listOf(StripItem(clip, StripItem.Kind.CLIP)) else emptyList())
            return
        }
        val seq = ++suggestSeq
        val slots = service.stripSlots()
        if (composing.isNotEmpty()) {
            val word = typedWord()
            val prev = previousWord()
            val level = if (allowAutocorrect) prefs.autocorrect else app.foldboard.core.text.AutocorrectLevel.OFF
            worker.execute {
                val r = e.suggest(word, prev, slots + 1, level)
                main.post {
                    if (seq != suggestSeq) return@post
                    val items = ArrayList<StripItem>()
                    if (r.corrected) {
                        items += StripItem(r.primary, StripItem.Kind.CORRECTION)
                        items += StripItem(r.typed, StripItem.Kind.TYPED)
                    } else {
                        items += StripItem(r.typed, if (r.typedIsValid) StripItem.Kind.PRIMARY else StripItem.Kind.TYPED)
                    }
                    r.alternatives.forEach { items += StripItem(it, StripItem.Kind.ALT) }
                    service.showSuggestions(items)
                }
            }
            return
        }
        val glided = lastGlide
        if (glided != null) {
            service.showSuggestions(listOf(StripItem(glided, StripItem.Kind.PRIMARY)) + glideAlternatives.map { StripItem(it, StripItem.Kind.ALT) })
            return
        }
        val items = ArrayList<StripItem>()
        service.freshClip()?.let { items += StripItem(it, StripItem.Kind.CLIP) }
        if (prefs.nextWord) {
            val before = ic?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            // Only predict at a word boundary.
            if (before.isEmpty() || before.last().isWhitespace()) {
                val prev = lastWordOf(before)
                val cap = shift != ShiftState.OFF
                worker.execute {
                    val preds = e.predict(prev, slots, cap)
                    main.post {
                        if (seq != suggestSeq) return@post
                        service.showSuggestions(items + preds.map { StripItem(it, StripItem.Kind.PREDICTION) })
                    }
                }
                return
            }
        }
        service.showSuggestions(items)
    }

    /** The word before the one being composed (null at the start of a sentence). */
    private fun previousWord(): String? {
        val c = ic ?: return null
        val before = c.getTextBeforeCursor(64 + composing.length, 0)?.toString() ?: return null
        return lastWordOf(before.dropLast(composing.length))
    }

    // ------------------------------------------------------------------ voice

    fun beginVoice() {
        if (composing.isNotEmpty()) commitComposing(false)
        voiceLen = 0
    }

    fun voicePartial(text: String) {
        val c = ic ?: return
        val joined = VoiceText.join(textBeforeVoice(), text, allowAutoCaps)
        c.setComposingText(joined, 1)
        voiceLen = joined.length.coerceAtLeast(1)
    }

    fun voiceFinal(text: String) {
        val c = ic ?: return
        val joined = VoiceText.join(textBeforeVoice(), text, allowAutoCaps)
        if (joined.isEmpty()) { if (voiceLen > 0) c.commitText("", 1) } else c.commitText(joined, 1)
        voiceLen = 0
    }

    fun endVoice() {
        if (voiceLen > 0) ic?.finishComposingText()
        voiceLen = 0
        updateShiftAuto()
        refreshSuggestions()
    }

    private fun textBeforeVoice(): String {
        val s = ic?.getTextBeforeCursor(64 + voiceLen, 0)?.toString().orEmpty()
        return if (voiceLen > 0) s.dropLast(voiceLen) else s
    }

    companion object {
        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        private val URL_EMAIL_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )

        fun isWordChar(c: Char) = c.isLetter() || c == '\''

        fun lastWordOf(s: CharSequence): String? {
            var i = s.length - 1
            while (i >= 0 && s[i] == ' ') i--
            if (i < 0 || !isWordChar(s[i])) return null
            val end = i + 1
            while (i >= 0 && isWordChar(s[i])) i--
            return s.substring(i + 1, end).trim('\'').ifEmpty { null }
        }
    }
}
