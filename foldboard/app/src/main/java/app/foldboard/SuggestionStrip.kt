package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

data class StripItem(val text: String, val kind: Kind) {
    enum class Kind { TYPED, PRIMARY, CORRECTION, ALT, PREDICTION, CLIP, ACTION }
}

enum class StripButton { SETTINGS, CLIPBOARD, MIC, EMOJI, RESIZE, HIDE }

interface StripListener {
    fun onPick(item: StripItem)
    fun onPickLong(item: StripItem)
    fun onStripButton(button: StripButton)
}

/**
 * Top bar: toolbar toggle on the left, mic on the right. In between: 3 (folded) or 5 (unfolded)
 * suggestions with the best in the middle, edit actions while text is selected, the live voice
 * transcript while dictating, or the toolbar (clipboard, emoji, resize, settings, hide).
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(context: Context, private val listener: StripListener) : View(context) {

    var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
        set(value) { field = value; invalidate() }
    var slots = 3
        set(value) { field = value; invalidate() }
    /** Items in priority order: best first. Placed center, left, right, far-left, far-right. */
    var items: List<StripItem> = emptyList()
        set(value) {
            if (value != field) { field = value; changedAt = SystemClock.uptimeMillis() }
            invalidate()
        }
    /** Non-null while dictating: shown instead of suggestions. */
    var voiceText: String? = null
        set(value) { field = value; invalidate() }
    var micActive = false
        set(value) { field = value; if (!value) micLevel = 0f; invalidate() }
    /** 0..1 microphone loudness, animates the mic button while listening. */
    var micLevel = 0f
        set(value) { field = field * 0.6f + value * 0.4f; invalidate() }
    var toolbarOpen = false
        set(value) { field = value; changedAt = SystemClock.uptimeMillis(); invalidate() }

    private var changedAt = 0L
    private var message: String? = null
    private val clearMessage = Runnable { message = null; invalidate() }

    fun flash(msg: String, ms: Long = 2600) {
        message = msg
        removeCallbacks(clearMessage)
        postDelayed(clearMessage, ms)
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val heightPx = (46 * density).toInt()
    private val buttonW = 50 * density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16f * density }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val tools = listOf(StripButton.CLIPBOARD, StripButton.EMOJI, StripButton.RESIZE, StripButton.SETTINGS, StripButton.HIDE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightPx)
    }

    private fun slotOrder(n: Int): IntArray = when (n) {
        1 -> intArrayOf(0)
        3 -> intArrayOf(1, 0, 2)
        5 -> intArrayOf(2, 1, 3, 0, 4)
        else -> IntArray(n) { it }
    }

    private fun displayed(): Array<StripItem?> {
        // Edit actions are shown left to right, in order.
        if (items.isNotEmpty() && items.all { it.kind == StripItem.Kind.ACTION }) {
            return Array(items.size) { items[it] }
        }
        val out = arrayOfNulls<StripItem>(slots)
        val order = slotOrder(slots)
        for (i in 0 until minOf(slots, items.size)) out[order[i]] = items[i]
        return out
    }

    private val slotLeft get() = buttonW
    private val slotRight get() = width - buttonW

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.strip)
        val cy = height / 2f
        val now = SystemClock.uptimeMillis()
        val fadeIn = ((now - changedAt) / 140f).coerceIn(0f, 1f)
        if (fadeIn < 1f) postInvalidateOnAnimation()

        // Toolbar toggle.
        icon.color = if (toolbarOpen) theme.accent else theme.textDim
        if (toolbarOpen) Icons.close(canvas, buttonW / 2, cy, 26 * density, icon) else Icons.grid(canvas, buttonW / 2, cy, 26 * density, icon)

        // Mic with a level pulse.
        val mx = width - buttonW / 2
        if (micActive) {
            fill.color = theme.accent
            fill.alpha = 70
            canvas.drawCircle(mx, cy, (17 + 9 * micLevel) * density, fill)
            fill.alpha = 255
            canvas.drawCircle(mx, cy, 16 * density, fill)
            icon.color = theme.onAccent
            postInvalidateOnAnimation()
        } else icon.color = theme.textDim
        Icons.mic(canvas, mx, cy, 24 * density, icon, micActive)

        val avail = slotRight - slotLeft
        val voice = voiceText
        val msg = message
        if (voice != null || msg != null) {
            textPaint.color = if (voice != null) theme.text else theme.textDim
            textPaint.typeface = regular
            val s = voice?.ifEmpty { "Listening…" } ?: msg!!
            val t = TextUtils.ellipsize(s, textPaint, avail - 24 * density, if (voice != null) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, slotLeft + avail / 2, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            return
        }
        if (toolbarOpen) {
            val w = avail / tools.size
            for ((i, b) in tools.withIndex()) {
                val x = slotLeft + w * i + w / 2
                if (pressedSlot == i) { fill.color = theme.keyFunction; canvas.drawCircle(x, cy, 19 * density, fill) }
                icon.color = theme.text
                icon.alpha = (255 * fadeIn).toInt()
                val s = 24 * density
                when (b) {
                    StripButton.CLIPBOARD -> Icons.clipboard(canvas, x, cy, s, icon)
                    StripButton.EMOJI -> Icons.emoji(canvas, x, cy, s, icon)
                    StripButton.RESIZE -> Icons.resize(canvas, x, cy, s, icon)
                    StripButton.SETTINGS -> Icons.gear(canvas, x, cy, s, icon)
                    StripButton.HIDE -> Icons.chevronDown(canvas, x, cy, s, icon)
                    StripButton.MIC -> {}
                }
                icon.alpha = 255
            }
            return
        }
        val shown = displayed()
        val w = avail / shown.size
        for ((i, item) in shown.withIndex()) {
            val l = slotLeft + i * w
            if (i > 0) {
                fill.color = theme.divider
                canvas.drawRect(l - density / 2, cy - 10 * density, l + density / 2, cy + 10 * density, fill)
            }
            item ?: continue
            if (pressedSlot == i) {
                fill.color = theme.keyFunction
                canvas.drawRoundRect(l + 3 * density, 5 * density, l + w - 3 * density, height - 5 * density, 12 * density, 12 * density, fill)
            }
            val label = when (item.kind) {
                StripItem.Kind.TYPED -> "“${item.text}”"
                StripItem.Kind.CLIP -> "⎘  " + item.text.replace('\n', ' ')
                else -> item.text
            }
            textPaint.typeface = when (item.kind) {
                StripItem.Kind.PRIMARY, StripItem.Kind.CORRECTION, StripItem.Kind.ACTION -> medium
                else -> regular
            }
            textPaint.color = when (item.kind) {
                StripItem.Kind.CORRECTION, StripItem.Kind.ACTION -> theme.accent
                StripItem.Kind.TYPED, StripItem.Kind.CLIP -> theme.textDim
                else -> theme.text
            }
            textPaint.alpha = (255 * (0.35f + 0.65f * fadeIn)).toInt()
            val t = TextUtils.ellipsize(label, textPaint, w - 12 * density, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, l + w / 2, cy - (textPaint.descent() + textPaint.ascent()) / 2 + 2 * density * (1f - fadeIn), textPaint)
            textPaint.alpha = 255
        }
    }

    private var pressedSlot = -1
    private var downTarget = -1 // -2 toolbar toggle, -4 mic, >=0 slot
    private var longFired = false
    private val longPress = Runnable {
        val item = if (toolbarOpen) null else displayed().getOrNull(downTarget)
        if (item != null) { longFired = true; listener.onPickLong(item) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longFired = false
                downTarget = targetAt(e.x)
                if (downTarget >= 0 && voiceText == null) { pressedSlot = downTarget; postDelayed(longPress, 550) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val t = targetAt(e.x)
                if (t == downTarget && !longFired) when {
                    voiceText != null && t >= 0 -> listener.onStripButton(StripButton.MIC)
                    t == -2 -> toolbarOpen = !toolbarOpen
                    t == -4 -> listener.onStripButton(StripButton.MIC)
                    toolbarOpen && t >= 0 -> tools.getOrNull(t)?.let { toolbarOpen = false; listener.onStripButton(it) }
                    t >= 0 -> displayed().getOrNull(t)?.let { listener.onPick(it) }
                }
                pressedSlot = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressedSlot = -1; invalidate() }
        }
        return true
    }

    private fun targetAt(x: Float): Int {
        if (x < slotLeft) return -2
        if (x >= slotRight) return -4
        val n = if (toolbarOpen) tools.size else displayed().size
        return ((x - slotLeft) / ((slotRight - slotLeft) / n)).toInt().coerceIn(0, n - 1)
    }
}
