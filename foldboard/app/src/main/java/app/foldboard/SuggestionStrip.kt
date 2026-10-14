package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

data class StripItem(val text: String, val kind: Kind) {
    enum class Kind { TYPED, PRIMARY, CORRECTION, ALT, PREDICTION, CLIP }
}

enum class StripButton { SETTINGS, CLIPBOARD, MIC }

interface StripListener {
    fun onPick(item: StripItem)
    fun onPickLong(item: StripItem)
    fun onStripButton(button: StripButton)
}

/**
 * Suggestion bar: settings + clipboard buttons on the left, mic on the right, and 3 (folded)
 * or 5 (unfolded) suggestion slots with the best one in the middle.
 */
@SuppressLint("ViewConstructor")
class SuggestionStrip(context: Context, private val listener: StripListener) : View(context) {

    var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
        set(value) { field = value; invalidate() }
    var slots = 3
        set(value) { field = value; invalidate() }
    /** Items in priority order: best first. Placed center, left, right, far-left, far-right. */
    var items: List<StripItem> = emptyList()
        set(value) { field = value; invalidate() }
    /** Non-null while dictating: shown instead of suggestions. */
    var voiceText: String? = null
        set(value) { field = value; invalidate() }
    var micActive = false
        set(value) { field = value; invalidate() }

    private var message: String? = null
    private val clearMessage = Runnable { message = null; invalidate() }

    fun flash(msg: String, ms: Long = 2600) {
        message = msg
        removeCallbacks(clearMessage)
        postDelayed(clearMessage, ms)
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val heightPx = (44 * density).toInt()
    private val buttonW = 46 * density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16.5f * density }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

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
        val out = arrayOfNulls<StripItem>(slots)
        val order = slotOrder(slots)
        for (i in 0 until minOf(slots, items.size)) out[order[i]] = items[i]
        return out
    }

    private val slotLeft get() = buttonW * 2
    private val slotRight get() = width - buttonW
    private val slotW get() = (slotRight - slotLeft) / slots

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.strip)
        val cy = height / 2f
        icon.color = theme.textDim
        Icons.gear(canvas, buttonW / 2, cy, 26 * density, icon)
        Icons.clipboard(canvas, buttonW * 1.5f, cy, 26 * density, icon)
        if (micActive) {
            fill.color = theme.accent
            canvas.drawCircle(width - buttonW / 2, cy, 17 * density, fill)
            icon.color = theme.onAccent
        }
        Icons.mic(canvas, width - buttonW / 2, cy, 26 * density, icon, micActive)

        val avail = slotRight - slotLeft
        val voice = voiceText
        val msg = message
        if (voice != null || msg != null) {
            textPaint.color = if (voice != null) theme.text else theme.textDim
            textPaint.typeface = Typeface.DEFAULT
            val s = voice?.ifEmpty { "Listening…" } ?: msg!!
            val t = TextUtils.ellipsize(s, textPaint, avail - 24 * density, if (voice != null) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, slotLeft + avail / 2, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            return
        }
        val shown = displayed()
        val w = slotW
        for ((i, item) in shown.withIndex()) {
            val l = slotLeft + i * w
            if (i > 0) {
                fill.color = theme.divider
                canvas.drawRect(l - density / 2, cy - 11 * density, l + density / 2, cy + 11 * density, fill)
            }
            item ?: continue
            if (pressedSlot == i) {
                fill.color = theme.keyFunction
                canvas.drawRoundRect(l + 3 * density, 4 * density, l + w - 3 * density, height - 4 * density, 10 * density, 10 * density, fill)
            }
            val label = when (item.kind) {
                StripItem.Kind.TYPED -> "“${item.text}”"
                StripItem.Kind.CLIP -> "⎘ " + item.text.replace('\n', ' ')
                else -> item.text
            }
            textPaint.typeface = if (item.kind == StripItem.Kind.PRIMARY || item.kind == StripItem.Kind.CORRECTION) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = when (item.kind) {
                StripItem.Kind.CORRECTION -> theme.accent
                StripItem.Kind.TYPED, StripItem.Kind.CLIP -> theme.textDim
                else -> theme.text
            }
            val t = TextUtils.ellipsize(label, textPaint, w - 10 * density, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, l + w / 2, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }

    private var pressedSlot = -1
    private var downTarget = -1 // -2 settings, -3 clipboard, -4 mic, >=0 slot
    private var longFired = false
    private val longPress = Runnable {
        val item = displayed().getOrNull(downTarget)
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
                    t == -2 -> listener.onStripButton(StripButton.SETTINGS)
                    t == -3 -> listener.onStripButton(StripButton.CLIPBOARD)
                    t == -4 -> listener.onStripButton(StripButton.MIC)
                    t >= 0 -> displayed().getOrNull(t)?.let { listener.onPick(it) }
                }
                pressedSlot = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressedSlot = -1; invalidate() }
        }
        return true
    }

    private fun targetAt(x: Float): Int = when {
        x < buttonW -> -2
        x < buttonW * 2 -> -3
        x >= slotRight -> -4
        else -> ((x - slotLeft) / slotW).toInt().coerceIn(0, slots - 1)
    }
}
