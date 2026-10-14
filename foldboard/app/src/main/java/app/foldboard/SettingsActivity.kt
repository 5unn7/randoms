package app.foldboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.foldboard.core.layout.Alphabets
import app.foldboard.core.layout.Key
import app.foldboard.core.layout.KeyShape
import app.foldboard.core.layout.KeyType
import app.foldboard.core.layout.LayoutFactory
import app.foldboard.core.layout.LayoutSpec

/** Setup wizard + all settings, built in code (no XML layouts, no support libraries). */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var preview: KeyboardView? = null
    private val setupRows = ArrayList<() -> Unit>()

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cText get() = if (dark) 0xFFF1F2F5.toInt() else 0xFF15161A.toInt()
    private val cDim get() = if (dark) 0xFF9DA1AB.toInt() else 0xFF5F636D.toInt()
    private val cCard get() = if (dark) 0xFF181B21.toInt() else 0xFFFFFFFF.toInt()
    private val cBg get() = if (dark) 0xFF0E1116.toInt() else 0xFFF4F5F8.toInt()
    private val cAccent get() = Prefs.ACCENTS.firstOrNull { it.first == prefs.accent }?.second ?: 0xFF4BA3F0.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) requestMic()
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        refreshSetup()
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == Prefs.ACCENT || key == Prefs.THEME) { build(); return }
        updatePreview()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isDefault(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun refreshSetup() = setupRows.forEach { it() }

    // ------------------------------------------------------------------ UI

    private fun build() {
        setupRows.clear()
        window.decorView.setBackgroundColor(cBg)
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val pad = dp(16)
        list.setPadding(pad, pad, pad, dp(40))

        list.addView(TextView(this).apply {
            text = "FoldBoard"
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText)
        })
        list.addView(TextView(this).apply {
            text = "The fast, smart keyboard built for foldables. Honeycomb or classic keys, split when unfolded, autocorrect, voice and glide."
            textSize = 14f; setTextColor(cDim); setPadding(0, dp(4), 0, dp(12))
        })

        // Setup
        card("Get started") {
            setupStep("1. Turn on FoldBoard", "Enable it in Android's keyboard list", { isEnabled() }) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            setupStep("2. Make it your keyboard", "Pick FoldBoard as the current input method", { isDefault() }) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            setupStep("3. Allow the microphone", "Needed only for voice typing", {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }) { requestMic() }
            addView(EditText(this@SettingsActivity).apply {
                hint = "Try it here…"
                setTextColor(cText); setHintTextColor(cDim)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
            }, matchWrap().apply { topMargin = dp(8) })
        }

        card("Layout") {
            addPreview(this)
            choice("Letter arrangement", Prefs.ALPHABET, "qwerty", Alphabets.all.map { it.id to it.displayName })
            choice("Key style", Prefs.KEY_STYLE, "hex", listOf("hex" to "Honeycomb (hexagon keys)", "classic" to "Classic (rectangular keys)"))
            choice("Split keyboard", Prefs.SPLIT_MODE, "auto", listOf("auto" to "Automatic: split when unfolded", "always" to "Always split", "never" to "Never split"))
            toggle("Number & symbol panel", "Between the halves when split", Prefs.CENTER_PANEL, true)
            toggle("Microphone key", "Also always available in the top bar", Prefs.MIC_KEY, true)
            toggle("Backspace key", "Off: swipe left on the letters to delete", Prefs.BACKSPACE_KEY, false)
            choice("One-handed mode (folded)", Prefs.ONE_HANDED, "off", listOf("off" to "Off", "left" to "Left hand", "right" to "Right hand"))
        }

        card("Size") {
            addView(TextView(this@SettingsActivity).apply {
                text = "Tip: on the keyboard, tap the ⠿ button (top-left), then Resize, to drag the keyboard's height, width and position like on Samsung Keyboard. Folded and unfolded sizes are saved separately."
                textSize = 13f; setTextColor(cDim); setPadding(0, dp(8), 0, dp(4))
            })
            slider("Height when folded", Prefs.HEIGHT_FOLDED, 100, 60, 160) { "$it%" }
            slider("Height when unfolded", Prefs.HEIGHT_UNFOLDED, 100, 60, 160) { "$it%" }
            slider("Gap between split halves", Prefs.SPLIT_GAP, 30, 0, 80) { "%.1f keys".format(it / 10f) }
            slider("Space between keys", Prefs.KEY_GAP, 3, 0, 10) { "$it dp" }
            slider("Label size", Prefs.LABEL_SCALE, 100, 70, 150) { "$it%" }
            slider("Bottom padding", Prefs.BOTTOM_PADDING, 4, 0, 40) { "$it dp" }
        }

        card("Typing") {
            choice("Language & dictionary", Prefs.LANGUAGE, "en", Prefs.LANGUAGES)
            choice("Autocorrect", Prefs.AUTOCORRECT, "normal", listOf("off" to "Off", "mild" to "Mild", "normal" to "Normal", "aggressive" to "Aggressive"))
            toggle("Suggestions", "Word completions above the keys", Prefs.SUGGESTIONS, true)
            toggle("Next-word prediction", "Learns which words you use together", Prefs.NEXT_WORD, true)
            toggle("Auto-capitalize", "Capital letter at the start of sentences", Prefs.AUTO_CAPS, true)
            toggle("Double-space for period", "Type two spaces to end a sentence", Prefs.DOUBLE_SPACE, true)
            toggle("Space after suggestion", "Picking a word adds a space", Prefs.AUTO_SPACE, true)
            toggle("Learn new words", "Names and slang you use are remembered on this device", Prefs.LEARN, true)
        }

        card("Gestures") {
            toggle("Swipe up = capital", "Flick a letter up for its capital", Prefs.SWIPE_UP, true)
            toggle("Swipe down = symbol", "Flick down for the small symbol on the key", Prefs.SWIPE_DOWN, true)
            toggle("Swipe left = delete", "Swipe left on any letter. Keep dragging or hold to delete more, drag back right to undo", Prefs.SWIPE_LEFT, true)
            choice("Swipe left deletes", Prefs.SWIPE_LEFT_MODE, "char", listOf("char" to "One character at a time", "word" to "A whole word"))
            toggle("Swipe right = restore word", "Bring back the last deleted word", Prefs.SWIPE_RIGHT, true)
            toggle("Space bar trackpad", "Slide on a space key to move the cursor, up/down to change lines", Prefs.SPACE_CURSOR, true)
            toggle("Double-tap space to select", "Double-tap and hold space, then drag to select text (like holding a mouse button)", Prefs.SPACE_SELECT, true)
            toggle("Glide typing (beta)", "Slide across letters to type a word. Works when the keyboard isn't split. Long letter swipes become words instead of deletes.", Prefs.GLIDE, false)
            slider("Long-press delay", Prefs.LONG_PRESS, 320, 150, 700) { "$it ms" }
        }

        card("Voice typing") {
            toggle("Keep listening", "Dictate across pauses until you tap stop", Prefs.VOICE_CONTINUOUS, true)
            toggle("Spoken punctuation", "Say “comma”, “period”, “new line” (English)", Prefs.VOICE_COMMANDS, true)
            toggle("Prefer on-device recognition", "Private and works offline when your phone supports it", Prefs.VOICE_OFFLINE, true)
            slider("Stop after silence", Prefs.VOICE_SILENCE, 20, 5, 90) { "$it s" }
        }

        card("Look & feel") {
            choice("Theme", Prefs.THEME, "system", listOf("system" to "Follow system", "dark" to "Dark", "amoled" to "Black (AMOLED)", "light" to "Light"))
            choice("Accent color", Prefs.ACCENT, "blue", Prefs.ACCENTS.map { it.first to it.first.replaceFirstChar { c -> c.uppercase() } })
            toggle("Show symbol hints", "Small swipe-down symbol in the key corner", Prefs.SHOW_SECONDARY, true)
            toggle("Key press preview", "Show the letter above your finger", Prefs.KEY_POPUP, true)
            slider("Vibration", Prefs.HAPTICS, 35, 0, 100) { if (it == 0) "Off" else "$it%" }
            slider("Key sound", Prefs.SOUND, 0, 0, 100) { if (it == 0) "Off" else "$it%" }
        }

        card("Privacy & data") {
            toggle("Clipboard history", "Keep recent copies for quick pasting", Prefs.CLIPBOARD, true)
            action("Clear clipboard history", "Pinned items are kept") {
                ClipboardHistory(prefs).clearUnpinned()
            }
            action("Forget learned words", "Erase your personal dictionary and word pairs") {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Forget learned words?")
                    .setMessage("Names, slang and word pairs FoldBoard learned from your typing will be erased.")
                    .setPositiveButton("Forget") { _, _ -> Engines.clearLearning(this@SettingsActivity) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addView(TextView(this@SettingsActivity).apply {
                text = "FoldBoard has no internet permission. What you type, learn and copy never leaves this device."
                textSize = 13f; setTextColor(cDim); setPadding(0, dp(10), 0, 0)
            })
        }

        setContentView(scroll)
        refreshSetup()
        updatePreview()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun card(title: String, body: LinearLayout.() -> Unit) {
        list.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            setTextColor(cAccent)
            setPadding(dp(4), dp(18), 0, dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(18).toFloat() }
        }
        card.body()
        list.addView(card, matchWrap())
    }

    private fun rowTexts(title: String, summary: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SettingsActivity).apply { text = title; textSize = 16f; setTextColor(cText) })
        if (summary != null) addView(TextView(this@SettingsActivity).apply {
            text = summary; textSize = 13f; setTextColor(cDim); tag = "summary"
        })
    }

    private fun LinearLayout.setupStep(title: String, summary: String, done: () -> Boolean, onClick: () -> Unit) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(rowTexts(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(this@SettingsActivity).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(badge)
        row.setOnClickListener { onClick() }
        addView(row, matchWrap())
        setupRows += {
            val ok = done()
            badge.text = if (ok) "Done" else "Set up"
            badge.setTextColor(if (ok) cDim else 0xFFFFFFFF.toInt())
            badge.background = GradientDrawable().apply {
                setColor(if (ok) 0x00000000 else cAccent); cornerRadius = dp(20).toFloat()
            }
        }
    }

    private fun LinearLayout.toggle(title: String, summary: String?, key: String, def: Boolean) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(rowTexts(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this@SettingsActivity).apply {
            isChecked = prefs.sp.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> prefs.sp.edit().putBoolean(key, v).apply() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        addView(row, matchWrap())
    }

    private fun LinearLayout.choice(title: String, key: String, def: String, options: List<Pair<String, String>>) {
        val texts = rowTexts(title, "")
        val summary = texts.findViewWithTag<TextView>("summary")
        fun current() = prefs.sp.getString(key, def) ?: def
        fun refresh() { summary.text = options.firstOrNull { it.first == current() }?.second ?: current() }
        refresh()
        texts.setPadding(0, dp(10), 0, dp(10))
        texts.setOnClickListener {
            val idx = options.indexOfFirst { it.first == current() }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                    prefs.sp.edit().putString(key, options[which].first).apply()
                    refresh()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addView(texts, matchWrap())
    }

    private fun LinearLayout.slider(title: String, key: String, def: Int, min: Int, max: Int, fmt: (Int) -> String) {
        val col = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val head = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this@SettingsActivity).apply { text = title; textSize = 16f; setTextColor(cText) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this@SettingsActivity).apply { textSize = 14f; setTextColor(cAccent) }
        head.addView(value)
        col.addView(head)
        val bar = SeekBar(this@SettingsActivity).apply {
            this.min = min
            this.max = max
            progress = prefs.sp.getInt(key, def)
            value.text = fmt(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = fmt(p)
                    if (fromUser) prefs.sp.edit().putInt(key, p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(bar, matchWrap())
        addView(col, matchWrap())
    }

    private fun LinearLayout.action(title: String, summary: String, onClick: () -> Unit) {
        val texts = rowTexts(title, summary)
        texts.setPadding(0, dp(10), 0, dp(10))
        texts.setOnClickListener { onClick() }
        addView(texts, matchWrap())
    }

    // ------------------------------------------------------------------ live preview

    private fun addPreview(parent: LinearLayout) {
        val v = KeyboardView(this, object : KeyboardListener {
            override fun onKeyDown(key: Key) {}
            override fun onText(text: String, key: Key?, x: Float, y: Float, forceUpper: Boolean) {}
            override fun onAction(type: KeyType) {}
            override fun onLongPressAction(type: KeyType) = false
            override fun onDeleteScrub(count: Int) {}
            override fun onDeleteScrubEnd() {}
            override fun onDeleteWord() {}
            override fun onRestoreWord() {}
            override fun onSelectStart() {}
            override fun onSelectEnd() {}
            override fun onCursorMove(dx: Int, dy: Int, select: Boolean) {}
            override fun onGlide(xs: FloatArray, ys: FloatArray, count: Int) {}
            override fun onResize(dHeight: Float, dLeft: Float, dRight: Float, dBottom: Float) {}
            override fun onResizeDone(reset: Boolean) {}
        })
        v.clipToOutline = true
        v.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        v.background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(14).toFloat() }
        preview = v
        parent.addView(v, matchWrap().apply { topMargin = dp(10); bottomMargin = dp(6) })
    }

    private fun updatePreview() {
        val v = preview ?: return
        val unfolded = resources.configuration.screenWidthDp >= 600
        val split = when (prefs.splitMode) { "always" -> true; "never" -> false; else -> unfolded }
        val shape = if (prefs.keyStyle == "classic") KeyShape.RECT else KeyShape.HEX
        val layout = LayoutFactory.build(
            LayoutSpec(Alphabets.byId(prefs.alphabet), shape = shape, split = split, centerPanel = split && prefs.centerPanel, splitGap = prefs.splitGap / 10f, micKey = prefs.micKey, backspaceKey = prefs.backspaceKey)
        )
        val scale = prefs.height(unfolded) / 100f
        val rows = layout.rows + if (shape == KeyShape.HEX) 1f / 3f else 0f
        val unitDp = (resources.configuration.screenWidthDp - 64) / layout.width
        val rowDp = (if (shape == KeyShape.HEX) unitDp / (1.1547f * 0.8f) else unitDp * 1.3f).coerceIn(30f, 64f)
        v.theme = KbTheme.resolve(this, prefs)
        v.config = ViewConfig(
            heightPx = (rows * rowDp * scale * resources.displayMetrics.density).toInt(),
            keyGapPx = dp(prefs.keyGap).toFloat(), labelScale = prefs.labelScale / 100f, showSecondary = prefs.showSecondary,
            oneHanded = if (unfolded) "off" else prefs.oneHanded, swipeUpCaps = false, swipeDownSymbol = false,
            swipeLeftDelete = false, swipeLeftChars = true, swipeRightRestore = false, spaceCursor = false, spaceSelect = false,
            glide = false, longPressMs = 400, keyPopup = false,
        )
        v.spaceLabel = Prefs.LANGUAGES.firstOrNull { it.first == prefs.language }?.second ?: ""
        v.layout = layout
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}
