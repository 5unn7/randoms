package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Clipboard history: tap to paste, long-press to pin/unpin. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val history: ClipboardHistory,
    private val onPaste: (String) -> Unit,
    private val onAbc: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
    private val list = LinearLayout(context)
    private val header = LinearLayout(context)

    init {
        orientation = VERTICAL
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, (44 * density).toInt()))
        list.orientation = VERTICAL
        val pad = (8 * density).toInt()
        list.setPadding(pad, pad / 2, pad, pad)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun applyTheme(t: KbTheme) { theme = t; setBackgroundColor(t.background); refresh() }

    fun refresh() {
        header.removeAllViews()
        header.setBackgroundColor(theme.strip)
        header.addView(button("ABC", true) { onAbc() }, LayoutParams((72 * density).toInt(), LayoutParams.MATCH_PARENT))
        header.addView(TextView(context).apply {
            text = "Clipboard · long-press to pin"
            setTextColor(theme.textDim); textSize = 13f; gravity = Gravity.CENTER
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(button("Clear", false) { history.clearUnpinned(); refresh() }, LayoutParams((80 * density).toInt(), LayoutParams.MATCH_PARENT))

        list.removeAllViews()
        if (history.entries.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Copied text shows up here."
                setTextColor(theme.textDim); textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, (40 * density).toInt(), 0, 0)
            })
        }
        for (e in history.entries) {
            list.addView(TextView(context).apply {
                text = (if (e.pinned) "📌 " else "") + e.text
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.text)
                textSize = 15f
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply { setColor(theme.key); cornerRadius = 12 * density }
                setOnClickListener { onPaste(e.text) }
                setOnLongClickListener { history.togglePin(e.text); refresh(); true }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun button(label: String, bold: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(if (bold) theme.text else theme.accent)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textSize = 15f
        setOnClickListener { onClick() }
    }
}
