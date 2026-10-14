package app.foldboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.foldboard.core.layout.Alphabets
import app.foldboard.core.layout.Key
import app.foldboard.core.layout.KeyShape
import app.foldboard.core.layout.KeyType
import app.foldboard.core.layout.KeyboardLayout
import app.foldboard.core.layout.LayoutFactory
import app.foldboard.core.layout.LayoutSpec
import app.foldboard.core.layout.Page
import app.foldboard.core.text.TextEngine
import app.foldboard.core.text.VoiceText
import java.util.Locale

class FoldBoardService : InputMethodService(), KeyboardListener, StripListener, VoiceInput.Callback {

    lateinit var prefs: Prefs
        private set
    private lateinit var logic: InputLogic
    private lateinit var feedback: Feedback
    private lateinit var clipboard: ClipboardHistory
    private lateinit var voice: VoiceInput

    private var strip: SuggestionStrip? = null
    private var keyboard: KeyboardView? = null
    private var content: FrameLayout? = null
    private var emojiPanel: EmojiPanel? = null
    private var clipPanel: ClipboardPanel? = null
    private var theme = KbTheme.dark(0xFF4BA3F0.toInt())

    private var page = Page.LETTERS
    private var unfolded = false
    private var keyboardHeightPx = 0
    private var lettersLayout: KeyboardLayout? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feedback = Feedback(this)
        clipboard = ClipboardHistory(prefs)
        voice = VoiceInput(this, this)
        logic = InputLogic(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener(clipListener)
        loadEngine()
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipListener)
        voice.destroy()
        Engines.save(this)
        super.onDestroy()
    }

    private fun loadEngine() {
        val lang = prefs.language
        if (logic.engine?.language == lang) return
        logic.engine = null
        Engines.load(this, lang) { e: TextEngine ->
            if (e.language != prefs.language) return@load
            logic.engine = e
            lettersLayout?.let { e.setLayout(it) }
            logic.refreshSuggestions()
        }
    }

    // ------------------------------------------------------------------ views

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = SuggestionStrip(this, this)
        val k = KeyboardView(this, this)
        val c = FrameLayout(this)
        c.addView(k, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val nav = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, nav + dp(prefs.bottomPadding))
            insets
        }
        strip = s; keyboard = k; content = c
        emojiPanel = null; clipPanel = null
        applySettings()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadEngine()
        val cls = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        page = when (cls) {
            android.text.InputType.TYPE_CLASS_NUMBER, android.text.InputType.TYPE_CLASS_PHONE,
            android.text.InputType.TYPE_CLASS_DATETIME -> Page.SYMBOLS
            else -> Page.LETTERS
        }
        applySettings()
        showKeys()
        logic.startInput(info)
        keyboard?.enterLabel = logic.enterLabel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (voice.listening) voice.stop()
        logic.finishInput()
        keyboard?.cancelAll()
        Engines.save(this)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logic.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Re-read settings and rebuild the layout for the current posture (folded / unfolded). */
    private fun applySettings() {
        theme = KbTheme.resolve(this, prefs)
        feedback.hapticStrength = prefs.haptics
        feedback.soundVolume = prefs.sound
        unfolded = resources.configuration.screenWidthDp >= 600
        strip?.theme = theme
        strip?.slots = if (unfolded) 5 else 3
        content?.setBackgroundColor(theme.background)
        (strip?.parent as? View)?.setBackgroundColor(theme.background)
        emojiPanel?.applyTheme(theme)
        clipPanel?.applyTheme(theme)
        applyLayout()
    }

    private fun applyLayout() {
        val k = keyboard ?: return
        val split = when (prefs.splitMode) {
            "always" -> true
            "never" -> false
            else -> unfolded
        }
        val shape = if (prefs.keyStyle == "classic") KeyShape.RECT else KeyShape.HEX
        val spec = LayoutSpec(
            alphabet = Alphabets.byId(prefs.alphabet),
            page = page,
            shape = shape,
            split = split,
            centerPanel = split && prefs.centerPanel,
            splitGap = prefs.splitGap / 10f,
            micKey = prefs.micKey,
        )
        val layout = LayoutFactory.build(spec)
        if (page == Page.LETTERS) {
            lettersLayout = layout
            logic.engine?.setLayout(layout)
        }
        val rowDp = if (unfolded) 62f else 54f
        val scale = (if (unfolded) prefs.heightUnfolded else prefs.heightFolded) / 100f
        val rows = layout.rows + if (shape == KeyShape.HEX) 1f / 3f else 0f
        keyboardHeightPx = (rows * rowDp * scale * resources.displayMetrics.density + dp(6)).toInt()
        k.theme = theme
        k.config = ViewConfig(
            heightPx = keyboardHeightPx,
            keyGapPx = dp(prefs.keyGap).toFloat(),
            labelScale = prefs.labelScale / 100f,
            showSecondary = prefs.showSecondary,
            oneHanded = if (unfolded) "off" else prefs.oneHanded,
            swipeUpCaps = prefs.swipeUpCaps,
            swipeDownSymbol = prefs.swipeDownSymbol,
            swipeLeftDelete = prefs.swipeLeftDelete,
            swipeRightRestore = prefs.swipeRightRestore,
            spaceCursor = prefs.spaceCursor,
            glide = prefs.glide,
            longPressMs = prefs.longPressMs,
            keyPopup = prefs.keyPopup,
        )
        k.spaceLabel = Prefs.LANGUAGES.firstOrNull { it.first == prefs.language }?.second ?: ""
        k.layout = layout
        k.shift = logic.shift
        emojiPanel?.layoutParams?.height = keyboardHeightPx
        clipPanel?.layoutParams?.height = keyboardHeightPx
    }

    private fun showKeys() {
        val c = content ?: return
        emojiPanel?.let { c.removeView(it) }
        clipPanel?.let { c.removeView(it) }
        keyboard?.visibility = View.VISIBLE
    }

    private fun showEmoji() {
        val c = content ?: return
        val p = emojiPanel ?: EmojiPanel(this, prefs, { logic.commitRaw(it) }, { showKeys() }, { logic.onBackspace() }).also { emojiPanel = it }
        p.applyTheme(theme)
        clipPanel?.let { c.removeView(it) }
        if (p.parent == null) c.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx))
        keyboard?.visibility = View.INVISIBLE
    }

    private fun toggleClipboard() {
        val c = content ?: return
        val existing = clipPanel
        if (existing != null && existing.parent != null) { showKeys(); return }
        val p = existing ?: ClipboardPanel(this, clipboard, { logic.commitRaw(it); showKeys() }, { showKeys() }).also { clipPanel = it }
        p.applyTheme(theme)
        emojiPanel?.let { c.removeView(it) }
        if (p.parent == null) c.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx))
        keyboard?.visibility = View.INVISIBLE
    }

    // ------------------------------------------------------------------ callbacks from InputLogic

    fun onShiftChanged(state: ShiftState) { keyboard?.shift = state }

    fun showSuggestions(items: List<StripItem>) { strip?.items = items }

    fun stripSlots() = strip?.slots ?: 3

    fun flash(msg: String) { strip?.flash(msg) }

    fun freshClip(): String? = if (prefs.clipboardHistory) clipboard.fresh()?.text else null

    private fun captureClip() {
        if (!prefs.clipboardHistory) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = try { cm.primaryClip } catch (e: SecurityException) { null } ?: return
        if (Build.VERSION.SDK_INT >= 33 && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        clipboard.add(text)
        clipPanel?.refresh()
    }

    // ------------------------------------------------------------------ KeyboardListener

    override fun onKeyDown(key: Key) = feedback.keyPress(key.type != KeyType.CHAR)

    override fun onText(text: String, key: Key?, x: Float, y: Float, forceUpper: Boolean) {
        logic.onText(text, x, y, forceUpper)
    }

    override fun onAction(type: KeyType) {
        when (type) {
            KeyType.SPACE -> logic.onSpace()
            KeyType.BACKSPACE -> logic.onBackspace()
            KeyType.ENTER -> logic.onEnter()
            KeyType.SHIFT -> logic.onShiftKey()
            KeyType.SYMBOLS -> { page = Page.SYMBOLS; applyLayout() }
            KeyType.LETTERS -> { page = Page.LETTERS; applyLayout() }
            KeyType.SYMBOLS_ALT -> { page = if (page == Page.SYMBOLS) Page.SYMBOLS_ALT else Page.SYMBOLS; applyLayout() }
            KeyType.EMOJI -> showEmoji()
            KeyType.CLIPBOARD -> toggleClipboard()
            KeyType.MIC -> toggleVoice()
            KeyType.CHAR -> {}
        }
    }

    override fun onLongPressAction(type: KeyType): Boolean = when (type) {
        KeyType.SPACE -> {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true
        }
        KeyType.SHIFT -> { logic.lockShift(); true }
        KeyType.EMOJI, KeyType.CLIPBOARD -> { openSettings(); true }
        else -> false
    }

    override fun onDeleteWord() = logic.deleteWord()
    override fun onRestoreWord() = logic.restoreWord()
    override fun onCursorMove(delta: Int) = logic.moveCursor(delta)
    override fun onGlide(xs: FloatArray, ys: FloatArray, count: Int) = logic.onGlide(xs, ys, count)

    // ------------------------------------------------------------------ StripListener

    override fun onPick(item: StripItem) {
        feedback.keyPress()
        logic.onPick(item)
    }

    override fun onPickLong(item: StripItem) = logic.onPickLong(item)

    override fun onStripButton(button: StripButton) {
        feedback.keyPress(true)
        when (button) {
            StripButton.SETTINGS -> openSettings()
            StripButton.CLIPBOARD -> toggleClipboard()
            StripButton.MIC -> toggleVoice()
        }
    }

    private fun openSettings(extra: String? = null) {
        val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (extra != null) i.putExtra(extra, true)
        startActivity(i)
    }

    // ------------------------------------------------------------------ voice

    private fun toggleVoice() {
        if (voice.listening) { voice.stop(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            flash("Allow the microphone to use voice typing")
            openSettings(SettingsActivity.EXTRA_REQUEST_MIC)
            return
        }
        if (!voice.isAvailable()) { flash("No speech recognizer installed on this device"); return }
        logic.beginVoice()
        voice.start(languageTag(prefs.language), prefs.voiceContinuous, prefs.voiceOffline)
    }

    private fun languageTag(lang: String): String {
        val device = Locale.getDefault()
        if (device.language == lang) return device.toLanguageTag()
        return when (lang) {
            "en" -> "en-US"; "es" -> "es-ES"; "fr" -> "fr-FR"; "de" -> "de-DE"; "it" -> "it-IT"; "pt" -> "pt-BR"
            else -> lang
        }
    }

    override fun onVoicePartial(text: String) {
        if (text.isEmpty()) return
        strip?.voiceText = text
        logic.voicePartial(VoiceText.process(text, prefs.language, prefs.voiceCommands))
    }

    override fun onVoiceFinal(text: String) {
        logic.voiceFinal(VoiceText.process(text, prefs.language, prefs.voiceCommands))
        if (voice.listening) strip?.voiceText = ""
    }

    override fun onVoiceState(listening: Boolean, error: String?) {
        strip?.voiceText = if (listening) "" else null
        strip?.micActive = listening
        keyboard?.micActive = listening
        if (!listening) logic.endVoice()
        if (error != null) flash(error)
    }
}
