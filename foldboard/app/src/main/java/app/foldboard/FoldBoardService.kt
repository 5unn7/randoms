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

    private var root: LinearLayout? = null
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
            navInset = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            applyBottomPadding()
            insets
        }
        this.root = root
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
        keyboard?.resizeMode = false
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

    private var navInset = 0

    private fun applyBottomPadding() {
        root?.setPadding(0, 0, 0, navInset + dp(prefs.bottomPadding) + dp(prefs.bottomOffset(unfolded)))
    }

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
            backspaceKey = prefs.backspaceKey,
        )
        val layout = LayoutFactory.build(spec)
        if (page == Page.LETTERS) {
            lettersLayout = layout
            logic.engine?.setLayout(layout)
        }
        val insetL = prefs.insetLeft(unfolded) / 1000f
        val insetR = prefs.insetRight(unfolded) / 1000f
        // Row height follows key width so keys keep good proportions on every screen; the user's
        // height setting scales from there.
        val widthDp = resources.configuration.screenWidthDp * (1f - insetL - insetR)
        val unitDp = widthDp / layout.width
        val natural = if (shape == KeyShape.HEX) unitDp / (1.1547f * 0.8f) else unitDp * 1.3f
        val rowDp = natural.coerceIn(if (unfolded) 46f else 44f, if (unfolded) 64f else 58f)
        val scale = prefs.height(unfolded) / 100f
        val rows = layout.rows + if (shape == KeyShape.HEX) 1f / 3f else 0f
        keyboardHeightPx = (rows * rowDp * scale * resources.displayMetrics.density + dp(8)).toInt()
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
            swipeLeftChars = prefs.swipeLeftChars,
            swipeRightRestore = prefs.swipeRightRestore,
            spaceCursor = prefs.spaceCursor,
            spaceSelect = prefs.spaceSelect,
            glide = prefs.glide,
            longPressMs = prefs.longPressMs,
            keyPopup = prefs.keyPopup,
            insetLeft = insetL,
            insetRight = insetR,
        )
        applyBottomPadding()
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

    override fun onDeleteScrub(count: Int) {
        feedback.keyPress(special = false)
        logic.deleteScrub(count)
    }
    override fun onDeleteScrubEnd() = logic.deleteScrubEnd()
    override fun onDeleteWord() = logic.deleteWord()
    override fun onRestoreWord() = logic.restoreWord()
    override fun onSelectStart() { feedback.keyPress(true); logic.selectStart() }
    override fun onSelectEnd() = logic.selectEnd()
    override fun onCursorMove(dx: Int, dy: Int, select: Boolean) {
        feedback.keyPress(special = false)
        logic.moveCursor(dx, dy, select)
    }
    override fun onGlide(xs: FloatArray, ys: FloatArray, count: Int) = logic.onGlide(xs, ys, count)

    // ------------------------------------------------------------------ resize (Samsung-style handles)

    private var rHeight = 100f
    private var rLeft = 0f
    private var rRight = 0f
    private var rBottom = 0f

    private fun enterResize() {
        showKeys()
        rHeight = prefs.height(unfolded).toFloat()
        rLeft = prefs.insetLeft(unfolded).toFloat()
        rRight = prefs.insetRight(unfolded).toFloat()
        rBottom = prefs.bottomOffset(unfolded).toFloat()
        keyboard?.resizeMode = true
        flash("Resize: drag the handles, then tap Done")
    }

    override fun onResize(dHeight: Float, dLeft: Float, dRight: Float, dBottom: Float) {
        val w = (keyboard?.width ?: 1).coerceAtLeast(1)
        if (dHeight != 0f && keyboardHeightPx > 0) rHeight = (rHeight * (keyboardHeightPx + dHeight) / keyboardHeightPx).coerceIn(55f, 170f)
        val pmL = dLeft / w * 1000f
        val pmR = dRight / w * 1000f
        if (dLeft != 0f && dRight != 0f && pmL == -pmR) {
            // Move: shift sideways within the screen.
            val shift = pmL.coerceIn(-rLeft, rRight)
            rLeft += shift; rRight -= shift
        } else {
            rLeft = (rLeft + pmL).coerceIn(0f, 500f - rRight)
            rRight = (rRight + pmR).coerceIn(0f, 500f - rLeft)
        }
        rBottom = (rBottom + dBottom / resources.displayMetrics.density).coerceIn(0f, 240f)
        val e = prefs.sp.edit()
        e.putInt(if (unfolded) Prefs.HEIGHT_UNFOLDED else Prefs.HEIGHT_FOLDED, rHeight.toInt())
        e.putInt(if (unfolded) Prefs.INSET_L_U else Prefs.INSET_L_F, rLeft.toInt())
        e.putInt(if (unfolded) Prefs.INSET_R_U else Prefs.INSET_R_F, rRight.toInt())
        e.putInt(if (unfolded) Prefs.BOTTOM_U else Prefs.BOTTOM_F, rBottom.toInt())
        e.apply()
        applyLayout()
    }

    override fun onResizeDone(reset: Boolean) {
        if (reset) {
            rHeight = 100f; rLeft = 0f; rRight = 0f; rBottom = 0f
            onResize(0f, 0f, 0f, 0f)
            return
        }
        keyboard?.resizeMode = false
        emojiPanel?.layoutParams?.height = keyboardHeightPx
        clipPanel?.layoutParams?.height = keyboardHeightPx
    }

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
            StripButton.EMOJI -> showEmoji()
            StripButton.RESIZE -> enterResize()
            StripButton.HIDE -> requestHideSelf(0)
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
        voice.start(languageTag(prefs.language), prefs.voiceContinuous, prefs.voiceOffline, prefs.voiceSilenceSec)
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

    override fun onVoiceLevel(level: Float) { strip?.micLevel = level }

    override fun onVoiceState(listening: Boolean, error: String?) {
        strip?.voiceText = if (listening) "" else null
        strip?.micActive = listening
        keyboard?.micActive = listening
        if (!listening) logic.endVoice()
        if (error != null) flash(error)
    }
}
