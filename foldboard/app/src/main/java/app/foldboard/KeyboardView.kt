package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import app.foldboard.core.gesture.Gesture
import app.foldboard.core.gesture.GestureClassifier
import app.foldboard.core.layout.Key
import app.foldboard.core.layout.KeyShape
import app.foldboard.core.layout.KeyType
import app.foldboard.core.layout.KeyboardLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

enum class ShiftState { OFF, ON, LOCKED }

interface KeyboardListener {
    /** Finger landed on a key (for haptics/sound). */
    fun onKeyDown(key: Key)
    /** A character key produced text. (x, y) is the touch point in layout units for the spatial model. */
    fun onText(text: String, key: Key?, x: Float, y: Float, forceUpper: Boolean)
    fun onAction(type: KeyType)
    /** Long press on a function key; return true if handled (then no tap follows). */
    fun onLongPressAction(type: KeyType): Boolean
    fun onDeleteWord()
    fun onRestoreWord()
    fun onCursorMove(delta: Int)
    fun onGlide(xs: FloatArray, ys: FloatArray, count: Int)
}

/** Settings the view needs; rebuilt from [Prefs] when the keyboard is shown. */
data class ViewConfig(
    val heightPx: Int,
    val keyGapPx: Float,
    val labelScale: Float,
    val showSecondary: Boolean,
    val oneHanded: String,
    val swipeUpCaps: Boolean,
    val swipeDownSymbol: Boolean,
    val swipeLeftDelete: Boolean,
    val swipeRightRestore: Boolean,
    val spaceCursor: Boolean,
    val glide: Boolean,
    val longPressMs: Int,
    val keyPopup: Boolean,
)

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: KeyboardListener) : View(context) {

    var layout: KeyboardLayout? = null
        set(value) { field = value; cancelAll(); rebuildGeometry(); invalidate() }
    var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
        set(value) { field = value; invalidate() }
    var config: ViewConfig = ViewConfig(dp(240).toInt(), dp(3), 1f, true, "off", true, true, true, true, true, false, 320, true)
        set(value) { val h = field.heightPx != value.heightPx; field = value; if (h) requestLayout(); rebuildGeometry(); invalidate() }
    var shift = ShiftState.OFF
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Text for the enter key ("Go", "Send"...), or null for the return icon. */
    var enterLabel: String? = null
        set(value) { field = value; invalidate() }
    var spaceLabel: String = ""
        set(value) { field = value; invalidate() }
    var micActive = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private class KeyGeom(val key: Key, val path: Path?, val rect: RectF, val cx: Float, val cy: Float, val w: Float, val h: Float)

    private var geoms: List<KeyGeom> = emptyList()
    private var originX = 0f
    private var originY = 0f
    private var unitW = 1f
    private var rowH = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tmpRect = RectF()

    private fun dp(v: Int) = v * resources.displayMetrics.density
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), config.heightPx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildGeometry()

    // ------------------------------------------------------------------ geometry

    private fun rebuildGeometry() {
        val l = layout ?: return
        if (width == 0 || height == 0) return
        val padX = dp(3)
        val padY = dp(3)
        var areaW = width - 2 * padX
        var areaX = padX
        if (config.oneHanded != "off" && !l.split) {
            val full = areaW
            areaW *= 0.8f
            if (config.oneHanded == "right") areaX = padX + full - areaW
        }
        val hex = l.shape == KeyShape.HEX
        val areaH = height - 2 * padY
        rowH = if (hex) areaH / (l.rows + 1f / 3f) else areaH / l.rows
        originY = padY + if (hex) rowH / 6f else 0f
        unitW = areaW / l.width
        originX = areaX
        val gap = config.keyGapPx
        val radius = (minOf(unitW, rowH) * 0.16f).coerceAtLeast(dp(3))
        fill.pathEffect = if (hex) CornerPathEffect(radius) else null
        geoms = l.keys.map { k ->
            val cx = originX + k.cx * unitW
            val cy = originY + k.cy * rowH
            val w = k.w * unitW - gap
            if (hex) {
                val h = rowH / 0.75f - gap * 1.15f
                val p = Path().apply {
                    moveTo(cx, cy - h / 2)
                    lineTo(cx + w / 2, cy - h / 4)
                    lineTo(cx + w / 2, cy + h / 4)
                    lineTo(cx, cy + h / 2)
                    lineTo(cx - w / 2, cy + h / 4)
                    lineTo(cx - w / 2, cy - h / 4)
                    close()
                }
                KeyGeom(k, p, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), cx, cy, w, h)
            } else {
                val h = rowH - gap
                KeyGeom(k, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), cx, cy, w, h)
            }
        }
        cornerRadius = radius
    }

    private var cornerRadius = 8f

    private fun toUnitX(px: Float) = (px - originX) / unitW
    private fun toUnitY(py: Float) = (py - originY) / rowH

    private fun geomOf(k: Key) = geoms.firstOrNull { it.key === k }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.background)
        val pressed = ptrs.values.mapNotNullTo(HashSet()) { if (it.mode != Mode.CURSOR) it.key else null }
        val base = minOf(unitW, rowH)
        for (g in geoms) drawKey(canvas, g, g.key in pressed, base)
        // Glide trail.
        ptrs.values.firstOrNull { it.mode == Mode.GLIDE }?.let { p ->
            trail.color = theme.accent
            trail.alpha = 170
            trail.strokeWidth = dp(5)
            canvas.drawPath(p.trailPath, trail)
        }
        // Press preview bubble.
        if (config.keyPopup) for (p in ptrs.values) {
            val k = p.key ?: continue
            if (p.mode == Mode.NORMAL && k.type == KeyType.CHAR && !p.moved) drawPreview(canvas, k, base)
        }
        // Long-press alternates.
        ptrs.values.firstOrNull { it.mode == Mode.POPUP }?.let { drawAlternates(canvas, it, base) }
    }

    private fun keyColor(k: Key, pressed: Boolean): Int = when {
        pressed -> theme.keyPressed
        k.type == KeyType.ENTER -> theme.accent
        k.type == KeyType.SPACE -> theme.keySpace
        k.type == KeyType.SHIFT && shift != ShiftState.OFF -> theme.keyPressed
        k.type == KeyType.MIC && micActive -> theme.accent
        k.type == KeyType.CHAR -> theme.key
        else -> theme.keyFunction
    }

    private fun drawKey(c: Canvas, g: KeyGeom, pressed: Boolean, base: Float) {
        val k = g.key
        fill.color = keyColor(k, pressed)
        fill.style = Paint.Style.FILL
        if (g.path != null) c.drawPath(g.path, fill) else c.drawRoundRect(g.rect, cornerRadius, cornerRadius, fill)

        val fg = if (k.type == KeyType.ENTER || (k.type == KeyType.MIC && micActive)) theme.onAccent else theme.text
        icon.color = fg
        val iconSize = base * 0.5f * config.labelScale
        when (k.type) {
            KeyType.CHAR -> {
                val upper = shift != ShiftState.OFF && k.isLetter
                val label = if (upper) k.label.uppercase() else k.label
                text.color = fg
                text.typeface = Typeface.DEFAULT
                text.textSize = base * (if (label.length > 1) 0.34f else 0.46f) * config.labelScale
                c.drawText(label, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
                if (config.showSecondary && k.secondary != null) {
                    text.color = theme.textDim
                    text.textSize = base * 0.2f * config.labelScale
                    val sx = g.cx + g.w * (if (k.shape == KeyShape.HEX) 0.2f else 0.3f)
                    val sy = g.cy - g.h * (if (k.shape == KeyShape.HEX) 0.16f else 0.24f)
                    c.drawText(k.secondary!!, sx, sy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            KeyType.SPACE -> if (spaceLabel.isNotEmpty() && k.shape == KeyShape.RECT) {
                text.color = theme.textDim; text.typeface = Typeface.DEFAULT
                text.textSize = base * 0.26f * config.labelScale
                c.drawText(spaceLabel, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
            }
            KeyType.SHIFT -> Icons.shift(c, g.cx, g.cy, iconSize, icon, shift != ShiftState.OFF, shift == ShiftState.LOCKED)
            KeyType.BACKSPACE -> Icons.backspace(c, g.cx, g.cy, iconSize, icon)
            KeyType.ENTER -> {
                val l = enterLabel
                if (l == null) Icons.enter(c, g.cx, g.cy, iconSize, icon) else {
                    text.color = fg; text.typeface = Typeface.DEFAULT_BOLD
                    text.textSize = base * 0.26f * config.labelScale
                    c.drawText(l, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            KeyType.MIC -> Icons.mic(c, g.cx, g.cy, iconSize, icon, micActive)
            KeyType.EMOJI -> Icons.emoji(c, g.cx, g.cy, iconSize, icon)
            KeyType.CLIPBOARD -> Icons.clipboard(c, g.cx, g.cy, iconSize, icon)
            KeyType.SYMBOLS, KeyType.SYMBOLS_ALT, KeyType.LETTERS -> {
                text.color = fg; text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = base * 0.27f * config.labelScale
                c.drawText(k.label, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    private fun drawPreview(c: Canvas, k: Key, base: Float) {
        val g = geomOf(k) ?: return
        val w = g.w * 1.15f
        val h = rowH * 1.05f
        var top = g.rect.top - h - dp(4)
        if (top < 0) top = 0f
        tmpRect.set(g.cx - w / 2, top, g.cx + w / 2, top + h)
        fill.pathEffect = null
        fill.color = theme.popup
        c.drawRoundRect(tmpRect, cornerRadius * 1.3f, cornerRadius * 1.3f, fill)
        if (layout?.shape == KeyShape.HEX) fill.pathEffect = CornerPathEffect(cornerRadius)
        val label = if (shift != ShiftState.OFF && k.isLetter) k.label.uppercase() else k.label
        text.color = theme.text; text.typeface = Typeface.DEFAULT
        text.textSize = base * 0.62f * config.labelScale
        c.drawText(label, tmpRect.centerX(), tmpRect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawAlternates(c: Canvas, p: Ptr, base: Float) {
        val items = p.popupItems
        val cw = p.popupCellW
        val ch = rowH * 0.95f
        tmpRect.set(p.popupX, p.popupY, p.popupX + cw * items.size, p.popupY + ch)
        fill.pathEffect = null
        fill.color = theme.popup
        c.drawRoundRect(tmpRect, cornerRadius * 1.3f, cornerRadius * 1.3f, fill)
        for ((i, s) in items.withIndex()) {
            val l = p.popupX + i * cw
            if (i == p.popupSel) {
                fill.color = theme.accent
                tmpRect.set(l + dp(2), p.popupY + dp(2), l + cw - dp(2), p.popupY + ch - dp(2))
                c.drawRoundRect(tmpRect, cornerRadius, cornerRadius, fill)
            }
            text.color = if (i == p.popupSel) theme.onAccent else theme.text
            text.typeface = Typeface.DEFAULT
            text.textSize = base * 0.46f * config.labelScale
            val label = if (shift != ShiftState.OFF) s.uppercase() else s
            c.drawText(label, l + cw / 2, p.popupY + ch / 2 - (text.descent() + text.ascent()) / 2, text)
        }
        if (layout?.shape == KeyShape.HEX) fill.pathEffect = CornerPathEffect(cornerRadius)
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { NORMAL, POPUP, CURSOR, REPEAT, GLIDE, DONE }

    private inner class Ptr(val id: Int, val key: Key?, val downX: Float, val downY: Float) {
        var x = downX
        var y = downY
        var mode = Mode.NORMAL
        var moved = false
        var popupItems: List<String> = emptyList()
        var popupX = 0f
        var popupY = 0f
        var popupCellW = 0f
        var popupSel = 0
        var cursorAnchor = downX
        var repeatCount = 0
        val glideX = ArrayList<Float>()
        val glideY = ArrayList<Float>()
        val trailPath = Path()
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                if (mode != Mode.REPEAT) return
                listener.onAction(KeyType.BACKSPACE)
                repeatCount++
                postDelayed(this, if (repeatCount > 15) 30L else 55L)
            }
        }
    }

    private val ptrs = LinkedHashMap<Int, Ptr>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = ptrs[e.getPointerId(i)] ?: continue
                    if (p.mode == Mode.GLIDE) {
                        for (h in 0 until e.historySize) addGlidePoint(p, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    }
                    move(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                ptrs[e.getPointerId(i)]?.let { p ->
                    move(p, e.getX(i), e.getY(i))
                    up(p)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val key = layout?.keyAt(toUnitX(x), toUnitY(y))
        val p = Ptr(id, key, x, y)
        ptrs[id] = p
        if (key != null) {
            listener.onKeyDown(key)
            val longPressable = when (key.type) {
                KeyType.CHAR -> key.alternates.isNotEmpty()
                else -> true
            }
            val delay = when (key.type) {
                KeyType.BACKSPACE -> 380L
                KeyType.SPACE -> maxOf(650, config.longPressMs).toLong() // leave time to start a cursor drag
                else -> config.longPressMs.toLong()
            }
            if (longPressable) postDelayed(p.longPress, delay)
        }
        invalidate()
    }

    private fun move(p: Ptr, x: Float, y: Float) {
        p.x = x; p.y = y
        val key = p.key ?: return
        val dist = hypot(x - p.downX, y - p.downY)
        if (!p.moved && dist > unitW * 0.3f) {
            p.moved = true
            if (p.mode == Mode.NORMAL) removeCallbacks(p.longPress)
        }
        when (p.mode) {
            Mode.POPUP -> {
                val sel = floor((x - p.popupX) / p.popupCellW).toInt().coerceIn(0, p.popupItems.size - 1)
                if (sel != p.popupSel) { p.popupSel = sel; invalidate() }
            }
            Mode.CURSOR -> {
                val step = unitW * 0.4f
                val steps = ((x - p.cursorAnchor) / step).toInt()
                if (steps != 0) { listener.onCursorMove(steps); p.cursorAnchor += steps * step }
            }
            Mode.GLIDE -> { addGlidePoint(p, x, y); invalidate() }
            Mode.NORMAL -> {
                if (key.type == KeyType.SPACE && config.spaceCursor && abs(x - p.downX) > unitW * 0.6f) {
                    p.mode = Mode.CURSOR
                    p.cursorAnchor = x
                    removeCallbacks(p.longPress)
                    invalidate()
                } else if (config.glide && key.isLetter && layout?.split == false && ptrs.size == 1 && dist > unitW * 1.1f) {
                    p.mode = Mode.GLIDE
                    removeCallbacks(p.longPress)
                    addGlidePoint(p, p.downX, p.downY)
                    addGlidePoint(p, x, y)
                    invalidate()
                } else if (p.moved) invalidate()
            }
            Mode.REPEAT, Mode.DONE -> {}
        }
    }

    private fun addGlidePoint(p: Ptr, x: Float, y: Float) {
        if (p.glideX.isEmpty()) p.trailPath.moveTo(x, y) else p.trailPath.lineTo(x, y)
        p.glideX += toUnitX(x)
        p.glideY += toUnitY(y)
    }

    private fun onLongPress(p: Ptr) {
        val key = p.key ?: return
        if (p.mode != Mode.NORMAL || p.moved) return
        when (key.type) {
            KeyType.CHAR -> {
                val g = geomOf(key) ?: return
                p.popupItems = key.alternates
                p.popupCellW = (unitW * 0.95f).coerceAtLeast(dp(36))
                val total = p.popupCellW * p.popupItems.size
                p.popupX = (g.cx - p.popupCellW / 2).coerceAtMost(width - total - dp(2)).coerceAtLeast(dp(2))
                p.popupY = (g.rect.top - rowH * 0.95f - dp(6)).coerceAtLeast(0f)
                p.popupSel = floor((p.x - p.popupX) / p.popupCellW).toInt().coerceIn(0, p.popupItems.size - 1)
                p.mode = Mode.POPUP
                listener.onKeyDown(key)
            }
            KeyType.BACKSPACE -> {
                p.mode = Mode.REPEAT
                p.repeat.run()
            }
            else -> if (listener.onLongPressAction(key.type)) p.mode = Mode.DONE
        }
        invalidate()
    }

    private fun up(p: Ptr) {
        ptrs.remove(p.id)
        removeCallbacks(p.longPress)
        removeCallbacks(p.repeat)
        val key = p.key
        if (key == null) { invalidate(); return }
        when (p.mode) {
            Mode.POPUP -> p.popupItems.getOrNull(p.popupSel)?.let {
                listener.onText(it, key, toUnitX(p.downX), toUnitY(p.downY), false)
            }
            Mode.GLIDE -> {
                val l = layout
                val distinct = p.glideX.indices.mapNotNullTo(LinkedHashSet()) { l?.keyAt(p.glideX[it], p.glideY[it]) }.count { it.isLetter }
                if (distinct >= 3) listener.onGlide(p.glideX.toFloatArray(), p.glideY.toFloatArray(), p.glideX.size)
                else tap(p, key)
            }
            Mode.NORMAL -> tap(p, key)
            Mode.CURSOR, Mode.REPEAT, Mode.DONE -> {}
        }
        invalidate()
    }

    private fun tap(p: Ptr, key: Key) {
        val g = GestureClassifier.classify((p.x - p.downX) / unitW, (p.y - p.downY) / unitW)
        val ux = toUnitX(p.downX)
        val uy = toUnitY(p.downY)
        when (key.type) {
            KeyType.CHAR -> when {
                g == Gesture.SWIPE_UP && config.swipeUpCaps && key.isLetter -> listener.onText(key.label, key, ux, uy, true)
                g == Gesture.SWIPE_DOWN && config.swipeDownSymbol && key.secondary != null -> listener.onText(key.secondary!!, key, ux, uy, false)
                g == Gesture.SWIPE_LEFT && config.swipeLeftDelete -> listener.onDeleteWord()
                g == Gesture.SWIPE_RIGHT && config.swipeRightRestore -> listener.onRestoreWord()
                else -> listener.onText(key.label, key, ux, uy, false)
            }
            KeyType.SPACE -> when {
                g == Gesture.SWIPE_LEFT && config.swipeLeftDelete -> listener.onDeleteWord()
                g == Gesture.SWIPE_RIGHT && config.swipeRightRestore -> listener.onRestoreWord()
                else -> listener.onAction(KeyType.SPACE)
            }
            KeyType.BACKSPACE -> if (g == Gesture.SWIPE_LEFT) listener.onDeleteWord() else listener.onAction(KeyType.BACKSPACE)
            else -> listener.onAction(key.type)
        }
    }

    fun cancelAll() {
        for (p in ptrs.values) { removeCallbacks(p.longPress); removeCallbacks(p.repeat) }
        ptrs.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }
}
