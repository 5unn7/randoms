package app.foldboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.foldboard.core.gesture.Gesture
import app.foldboard.core.gesture.GestureClassifier
import app.foldboard.core.layout.Cluster
import app.foldboard.core.layout.Key
import app.foldboard.core.layout.KeyShape
import app.foldboard.core.layout.KeyType
import app.foldboard.core.layout.KeyboardLayout
import app.foldboard.core.layout.KeyboardLayout.Companion.ROW_PITCH
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ShiftState { OFF, ON, LOCKED }

interface KeyboardListener {
    /** Finger landed on a key (for haptics/sound). */
    fun onKeyDown(key: Key)
    /** A character key produced text. (x, y) is the touch point in layout units for the spatial model. */
    fun onText(text: String, key: Key?, x: Float, y: Float, forceUpper: Boolean)
    fun onAction(type: KeyType)
    /** Long press on a function key; return true if handled (then no tap follows). */
    fun onLongPressAction(type: KeyType): Boolean
    /** Swipe-left scrub: [count] characters should now be deleted in this gesture (can go down to restore). */
    fun onDeleteScrub(count: Int)
    fun onDeleteScrubEnd()
    fun onDeleteWord()
    fun onRestoreWord()
    /** Space-bar trackpad. With [select], the selection is extended (double-tap-and-drag). */
    fun onSelectStart()
    fun onSelectEnd()
    fun onCursorMove(dx: Int, dy: Int, select: Boolean)
    fun onGlide(xs: FloatArray, ys: FloatArray, count: Int)
    /** Resize mode: deltas in px. */
    fun onResize(dHeight: Float, dLeft: Float, dRight: Float, dBottom: Float)
    fun onResizeDone(reset: Boolean)
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
    /** Swipe left deletes characters (scrub) instead of whole words. */
    val swipeLeftChars: Boolean,
    val swipeRightRestore: Boolean,
    val spaceCursor: Boolean,
    val spaceSelect: Boolean,
    val glide: Boolean,
    val longPressMs: Int,
    val keyPopup: Boolean,
    /** Horizontal insets as fractions of the width (resize). */
    val insetLeft: Float = 0f,
    val insetRight: Float = 0f,
)

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: KeyboardListener) : View(context) {

    var layout: KeyboardLayout? = null
        set(value) { field = value; cancelAll(); rebuildGeometry(); invalidate() }
    var theme: KbTheme = KbTheme.dark(0xFF4BA3F0.toInt())
        set(value) { field = value; invalidate() }
    var config: ViewConfig = ViewConfig(dp(240).toInt(), dp(3), 1f, true, "off", true, true, true, true, true, true, true, false, 320, true)
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
    /** Samsung-style resize handles over the keyboard. */
    var resizeMode = false
        set(value) { field = value; cancelAll(); invalidate() }

    private class KeyGeom(val key: Key, val path: Path?, val rect: RectF, val cx: Float, val cy: Float, val w: Float, val h: Float)

    private var geoms: List<KeyGeom> = emptyList()
    private var originX = 0f
    private var originY = 0f
    private var unitW = 1f
    private var rowH = 1f
    private var mainOffset = 0f
    private var area = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tmpRect = RectF()
    private val letterFace: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val labelFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val ease = DecelerateInterpolator(1.6f)

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
        val padX = dp(4)
        val padY = dp(4)
        var areaX = width * config.insetLeft + padX
        var areaW = width * (1f - config.insetLeft - config.insetRight) - 2 * padX
        if (config.oneHanded != "off" && !l.split) {
            val full = areaW
            areaW *= 0.8f
            if (config.oneHanded == "right") areaX += full - areaW
        }
        val hex = l.shape == KeyShape.HEX
        val areaH = height - 2 * padY
        area.set(areaX, padY, areaX + areaW, padY + areaH)
        rowH = if (hex) areaH / (l.rows + 1f / 3f) else areaH / l.rows
        originY = padY + if (hex) rowH / 6f else 0f
        // Keep keys well proportioned: never wider than a slightly widened regular hexagon.
        val natural = areaW / l.width
        val maxUnit = if (hex) rowH * 1.1547f * 1.12f else rowH * 1.35f
        unitW = min(natural, maxUnit)
        val extra = areaW - unitW * l.width
        originX = areaX
        mainOffset = extra / 2f
        val gap = config.keyGapPx
        val base = min(unitW, rowH)
        cornerRadius = (base * 0.2f).coerceAtLeast(dp(4))
        fill.pathEffect = if (hex) CornerPathEffect(cornerRadius) else null
        shade.pathEffect = fill.pathEffect
        geoms = l.keys.map { k ->
            // Split halves hug the screen edges (thumb reach); the center panel stays centered.
            val off = when {
                !l.split -> mainOffset
                k.cluster == Cluster.RIGHT -> extra
                k.cluster == Cluster.CENTER -> extra / 2f
                else -> 0f
            }
            val cx = originX + off + k.cx * unitW
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
    }

    private var cornerRadius = 8f

    /** Pixel -> layout units, for the unsplit layout (glide typing). */
    private fun toUnitX(px: Float) = (px - originX - mainOffset) / unitW
    private fun toUnitY(py: Float) = (py - originY) / rowH

    private fun hit(x: Float, y: Float): KeyGeom? {
        val l = layout ?: return null
        if (l.shape == KeyShape.RECT) {
            val half = config.keyGapPx / 2
            geoms.firstOrNull { x >= it.rect.left - half && x < it.rect.right + half && y >= it.rect.top - half && y < it.rect.bottom + half }?.let { return it }
        }
        var best: KeyGeom? = null
        var bestD = Float.MAX_VALUE
        val pitch = if (l.shape == KeyShape.HEX) ROW_PITCH else 1f
        for (g in geoms) {
            val halfExtra = if (l.shape == KeyShape.RECT) max(0f, (g.key.w - 1f) * unitW / 2f) else 0f
            val dx = max(0f, abs(x - g.cx) - halfExtra) / unitW
            val dy = (y - g.cy) / rowH * pitch
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = g }
        }
        return if (bestD <= 0.81f) best else null
    }

    /** Touch point in the key's own layout units (for the spatial autocorrect model). */
    private fun unitPoint(g: KeyGeom, x: Float, y: Float): Pair<Float, Float> =
        (g.key.cx + (x - g.cx) / unitW) to (g.key.cy + (y - g.cy) / rowH)

    // ------------------------------------------------------------------ drawing

    private val released = HashMap<Key, Long>()
    private var trailFadeStart = 0L
    private var fadingTrail: Path? = null

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false
        canvas.drawColor(theme.background)
        val pressed = HashMap<Key, Long>()
        for (p in ptrs.values) if (p.mode != Mode.CURSOR && p.mode != Mode.SELECT) p.geom?.let { pressed[it.key] = p.downTime }
        val base = min(unitW, rowH)
        for (g in geoms) {
            val t = when {
                g.key in pressed -> 1f
                else -> released[g.key]?.let { r ->
                    val e = (now - r) / FADE_MS
                    if (e >= 1f) { released.remove(g.key); 0f } else { animating = true; 1f - ease.getInterpolation(e) }
                } ?: 0f
            }
            drawKey(canvas, g, t, base)
        }
        // Glide trail (live, then fading out).
        ptrs.values.firstOrNull { it.mode == Mode.GLIDE }?.let { p ->
            trail.color = theme.accent; trail.alpha = 190; trail.strokeWidth = dp(5)
            canvas.drawPath(p.trailPath, trail)
        }
        fadingTrail?.let { path ->
            val e = (now - trailFadeStart) / 260f
            if (e >= 1f) fadingTrail = null else {
                trail.color = theme.accent; trail.alpha = (190 * (1f - e)).toInt(); trail.strokeWidth = dp(5)
                canvas.drawPath(path, trail); animating = true
            }
        }
        // Press preview bubble.
        if (config.keyPopup) for (p in ptrs.values) {
            val g = p.geom ?: continue
            if (p.mode == Mode.NORMAL && g.key.type == KeyType.CHAR && !p.moved) {
                val e = ((now - p.downTime) / 90f).coerceAtMost(1f)
                if (e < 1f) animating = true
                drawPreview(canvas, g, base, ease.getInterpolation(e))
            }
        }
        // Long-press alternates.
        ptrs.values.firstOrNull { it.mode == Mode.POPUP }?.let {
            val e = ((now - it.popupTime) / 110f).coerceAtMost(1f)
            if (e < 1f) animating = true
            drawAlternates(canvas, it, base, ease.getInterpolation(e))
        }
        if (resizeMode) drawResize(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF; val aa = (a ushr 24)
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF; val ba = (b ushr 24)
        return ((aa + (ba - aa) * t).toInt() shl 24) or ((ar + (br - ar) * t).toInt() shl 16) or
            ((ag + (bg - ag) * t).toInt() shl 8) or (ab + (bb - ab) * t).toInt()
    }

    private fun baseColor(k: Key): Int = when {
        k.type == KeyType.ENTER -> theme.accent
        k.type == KeyType.SPACE -> theme.keySpace
        k.type == KeyType.SHIFT && shift != ShiftState.OFF -> theme.keyPressed
        k.type == KeyType.MIC && micActive -> theme.accent
        k.type == KeyType.CHAR -> theme.key
        else -> theme.keyFunction
    }

    private fun drawKey(c: Canvas, g: KeyGeom, press: Float, base: Float) {
        val k = g.key
        val color = blend(baseColor(k), if (k.type == KeyType.ENTER) theme.accentPressed else theme.keyPressed, press)
        val scale = 1f - 0.05f * press
        c.save()
        c.scale(scale, scale, g.cx, g.cy)
        // Soft bottom edge gives keys some depth.
        shade.color = theme.shadow
        c.save(); c.translate(0f, dp(1.2f))
        if (g.path != null) c.drawPath(g.path, shade) else c.drawRoundRect(g.rect, cornerRadius, cornerRadius, shade)
        c.restore()
        fill.color = color
        fill.style = Paint.Style.FILL
        if (g.path != null) c.drawPath(g.path, fill) else c.drawRoundRect(g.rect, cornerRadius, cornerRadius, fill)

        val fg = if (k.type == KeyType.ENTER || (k.type == KeyType.MIC && micActive)) theme.onAccent else theme.text
        icon.color = fg
        val iconSize = base * 0.46f * config.labelScale
        when (k.type) {
            KeyType.CHAR -> {
                val upper = shift != ShiftState.OFF && k.isLetter
                val label = if (upper) k.label.uppercase() else k.label
                text.color = fg
                text.typeface = letterFace
                text.textSize = base * (if (label.length > 1) 0.32f else 0.43f) * config.labelScale
                c.drawText(label, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
                if (config.showSecondary && k.secondary != null) {
                    text.color = theme.textDim
                    text.textSize = base * 0.19f * config.labelScale
                    val sx = g.cx + g.w * (if (k.shape == KeyShape.HEX) 0.21f else 0.3f)
                    val sy = g.cy - g.h * (if (k.shape == KeyShape.HEX) 0.17f else 0.25f)
                    c.drawText(k.secondary!!, sx, sy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            KeyType.SPACE -> if (spaceLabel.isNotEmpty() && k.shape == KeyShape.RECT) {
                text.color = theme.textDim; text.typeface = letterFace
                text.textSize = base * 0.25f * config.labelScale
                c.drawText(spaceLabel, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
            }
            KeyType.SHIFT -> Icons.shift(c, g.cx, g.cy, iconSize, icon, shift != ShiftState.OFF, shift == ShiftState.LOCKED)
            KeyType.BACKSPACE -> Icons.backspace(c, g.cx, g.cy, iconSize, icon)
            KeyType.ENTER -> {
                val l = enterLabel
                if (l == null) Icons.enter(c, g.cx, g.cy, iconSize, icon) else {
                    text.color = fg; text.typeface = labelFace
                    text.textSize = base * 0.25f * config.labelScale
                    c.drawText(l, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            KeyType.MIC -> Icons.mic(c, g.cx, g.cy, iconSize, icon, micActive)
            KeyType.EMOJI -> Icons.emoji(c, g.cx, g.cy, iconSize, icon)
            KeyType.CLIPBOARD -> Icons.clipboard(c, g.cx, g.cy, iconSize, icon)
            KeyType.SYMBOLS, KeyType.SYMBOLS_ALT, KeyType.LETTERS -> {
                text.color = fg; text.typeface = labelFace
                text.textSize = base * 0.25f * config.labelScale
                c.drawText(k.label, g.cx, g.cy - (text.descent() + text.ascent()) / 2, text)
            }
        }
        c.restore()
    }

    private fun drawPreview(c: Canvas, g: KeyGeom, base: Float, t: Float) {
        val k = g.key
        val w = max(g.w * 1.2f, dp(40))
        val h = rowH * 1.1f
        var top = g.rect.top - h - dp(6)
        if (top < 0) top = 0f
        val s = 0.7f + 0.3f * t
        c.save()
        c.scale(s, s, g.cx, top + h)
        tmpRect.set(g.cx - w / 2, top, g.cx + w / 2, top + h)
        fill.pathEffect = null
        fill.color = theme.shadow
        tmpRect.offset(0f, dp(2)); c.drawRoundRect(tmpRect, cornerRadius * 1.4f, cornerRadius * 1.4f, fill); tmpRect.offset(0f, -dp(2))
        fill.color = theme.popup
        c.drawRoundRect(tmpRect, cornerRadius * 1.4f, cornerRadius * 1.4f, fill)
        if (layout?.shape == KeyShape.HEX) fill.pathEffect = CornerPathEffect(cornerRadius)
        val label = if (shift != ShiftState.OFF && k.isLetter) k.label.uppercase() else k.label
        text.color = theme.text; text.typeface = letterFace
        text.alpha = (255 * t).toInt()
        text.textSize = base * 0.6f * config.labelScale
        c.drawText(label, tmpRect.centerX(), tmpRect.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.alpha = 255
        c.restore()
    }

    private fun drawAlternates(c: Canvas, p: Ptr, base: Float, t: Float) {
        val items = p.popupItems
        val cw = p.popupCellW
        val ch = rowH * 0.95f
        val s = 0.85f + 0.15f * t
        c.save()
        c.scale(s, s, p.popupX + cw * items.size / 2f, p.popupY + ch)
        tmpRect.set(p.popupX, p.popupY, p.popupX + cw * items.size, p.popupY + ch)
        fill.pathEffect = null
        fill.color = theme.shadow
        tmpRect.offset(0f, dp(2)); c.drawRoundRect(tmpRect, cornerRadius * 1.4f, cornerRadius * 1.4f, fill); tmpRect.offset(0f, -dp(2))
        fill.color = theme.popup
        c.drawRoundRect(tmpRect, cornerRadius * 1.4f, cornerRadius * 1.4f, fill)
        for ((i, str) in items.withIndex()) {
            val l = p.popupX + i * cw
            if (i == p.popupSel) {
                fill.color = theme.accent
                tmpRect.set(l + dp(3), p.popupY + dp(3), l + cw - dp(3), p.popupY + ch - dp(3))
                c.drawRoundRect(tmpRect, cornerRadius, cornerRadius, fill)
            }
            text.color = if (i == p.popupSel) theme.onAccent else theme.text
            text.typeface = letterFace
            text.textSize = base * 0.44f * config.labelScale
            val label = if (shift != ShiftState.OFF) str.uppercase() else str
            c.drawText(label, l + cw / 2, p.popupY + ch / 2 - (text.descent() + text.ascent()) / 2, text)
        }
        if (layout?.shape == KeyShape.HEX) fill.pathEffect = CornerPathEffect(cornerRadius)
        c.restore()
    }

    // ------------------------------------------------------------------ resize mode

    private enum class Handle { TOP, LEFT, RIGHT, MOVE, RESET, DONE }
    private val handleRects = HashMap<Handle, RectF>()
    private var dragHandle: Handle? = null
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun drawResize(c: Canvas) {
        fill.pathEffect = null
        fill.color = (theme.background and 0x00FFFFFF) or 0xB0000000.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val l = width * config.insetLeft
        val r = width * (1f - config.insetRight)
        // Frame.
        trail.color = theme.accent; trail.alpha = 255; trail.strokeWidth = dp(2)
        tmpRect.set(l + dp(2), dp(2), r - dp(2), height - dp(2))
        c.drawRoundRect(tmpRect, dp(14), dp(14), trail)
        fill.color = theme.accent
        val cx = (l + r) / 2
        val cy = height / 2f
        // Height handle (top), width handles (sides), move handle (center).
        handleRects[Handle.TOP] = RectF(cx - dp(40), 0f, cx + dp(40), dp(28)).also { c.drawRoundRect(RectF(cx - dp(28), dp(4), cx + dp(28), dp(10)), dp(3), dp(3), fill) }
        handleRects[Handle.LEFT] = RectF(l, cy - dp(44), l + dp(30), cy + dp(44)).also { c.drawRoundRect(RectF(l + dp(4), cy - dp(28), l + dp(10), cy + dp(28)), dp(3), dp(3), fill) }
        handleRects[Handle.RIGHT] = RectF(r - dp(30), cy - dp(44), r, cy + dp(44)).also { c.drawRoundRect(RectF(r - dp(10), cy - dp(28), r - dp(4), cy + dp(28)), dp(3), dp(3), fill) }
        val mr = dp(26)
        handleRects[Handle.MOVE] = RectF(cx - mr, cy - mr, cx + mr, cy + mr)
        c.drawCircle(cx, cy, mr, fill)
        icon.color = theme.onAccent
        Icons.move(c, cx, cy, mr * 1.3f, icon)
        // Buttons.
        text.typeface = labelFace; text.textSize = dp(15f)
        val bw = dp(84); val bh = dp(36); val by = height - bh - dp(10)
        val reset = RectF(cx - bw - dp(8), by, cx - dp(8), by + bh)
        val done = RectF(cx + dp(8), by, cx + bw + dp(8), by + bh)
        fill.color = theme.keyFunction; c.drawRoundRect(reset, bh / 2, bh / 2, fill)
        fill.color = theme.accent; c.drawRoundRect(done, bh / 2, bh / 2, fill)
        text.color = theme.text; c.drawText("Reset", reset.centerX(), reset.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.color = theme.onAccent; c.drawText("Done", done.centerX(), done.centerY() - (text.descent() + text.ascent()) / 2, text)
        handleRects[Handle.RESET] = reset
        handleRects[Handle.DONE] = done
        text.color = theme.textDim; text.typeface = letterFace; text.textSize = dp(12f)
        c.drawText("Drag the edges to resize · drag the center to move", cx, dp(44), text)
        if (layout?.shape == KeyShape.HEX) fill.pathEffect = CornerPathEffect(cornerRadius)
    }

    private fun resizeTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragHandle = handleRects.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
                lastRawX = e.rawX; lastRawY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastRawX
                val dy = e.rawY - lastRawY
                lastRawX = e.rawX; lastRawY = e.rawY
                when (dragHandle) {
                    Handle.TOP -> listener.onResize(-dy, 0f, 0f, 0f)
                    Handle.LEFT -> listener.onResize(0f, dx, 0f, 0f)
                    Handle.RIGHT -> listener.onResize(0f, 0f, -dx, 0f)
                    Handle.MOVE -> listener.onResize(0f, dx, -dx, -dy)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                val h = handleRects.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
                if (h == dragHandle && h == Handle.DONE) listener.onResizeDone(false)
                if (h == dragHandle && h == Handle.RESET) listener.onResizeDone(true)
                dragHandle = null
            }
        }
        return true
    }

    // ------------------------------------------------------------------ touch

    private enum class Mode { NORMAL, POPUP, CURSOR, SELECT, DELETE, REPEAT, GLIDE, DONE }

    private inner class Ptr(val id: Int, val geom: KeyGeom?, val downX: Float, val downY: Float) {
        val key: Key? get() = geom?.key
        val downTime = SystemClock.uptimeMillis()
        var x = downX
        var y = downY
        var mode = Mode.NORMAL
        var moved = false
        var doubleTapSpace = false
        var popupItems: List<String> = emptyList()
        var popupX = 0f
        var popupY = 0f
        var popupCellW = 0f
        var popupSel = 0
        var popupTime = 0L
        var anchorX = downX
        var anchorY = downY
        var repeatCount = 0
        var scrubGeo = 0
        var scrubExtra = 0
        var scrubSent = 0
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
        /** Holding still while scrub-deleting keeps deleting, faster over time. */
        val scrubRepeat: Runnable = object : Runnable {
            override fun run() {
                if (mode != Mode.DELETE) return
                scrubExtra++
                sendScrub(this@Ptr)
                postDelayed(this, if (scrubExtra > 10) 35L else 75L)
            }
        }
    }

    private val ptrs = LinkedHashMap<Int, Ptr>()
    private var lastSpaceTapUp = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (resizeMode) return resizeTouch(e)
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
        val g = hit(x, y)
        val p = Ptr(id, g, x, y)
        ptrs[id] = p
        val key = g?.key
        if (key != null) {
            listener.onKeyDown(key)
            if (key.type == KeyType.SPACE) p.doubleTapSpace = p.downTime - lastSpaceTapUp < 320
            val longPressable = key.type != KeyType.CHAR || key.alternates.isNotEmpty()
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
        val lastX = p.x; val lastY = p.y
        p.x = x; p.y = y
        val key = p.key ?: return
        val dx = x - p.downX
        val dy = y - p.downY
        val dist = hypot(dx, dy)
        if (!p.moved && dist > unitW * 0.3f) {
            p.moved = true
            if (p.mode == Mode.NORMAL) removeCallbacks(p.longPress)
        }
        when (p.mode) {
            Mode.POPUP -> {
                val sel = floor((x - p.popupX) / p.popupCellW).toInt().coerceIn(0, p.popupItems.size - 1)
                if (sel != p.popupSel) { p.popupSel = sel; invalidate() }
            }
            Mode.CURSOR, Mode.SELECT -> {
                val stepX = unitW * 0.38f
                val stepY = rowH * 0.8f
                val sx = ((x - p.anchorX) / stepX).toInt()
                val sy = ((y - p.anchorY) / stepY).toInt()
                if (sx != 0) { listener.onCursorMove(sx, 0, p.mode == Mode.SELECT); p.anchorX += sx * stepX }
                if (sy != 0) { listener.onCursorMove(0, sy, p.mode == Mode.SELECT); p.anchorY += sy * stepY }
            }
            Mode.DELETE -> {
                val geo = (floor((p.anchorX - x) / (unitW * 0.4f)).toInt() + 1).coerceAtLeast(0)
                if (geo != p.scrubGeo) {
                    if (geo < p.scrubGeo) p.scrubExtra = 0 // moving back right restores
                    p.scrubGeo = geo
                    sendScrub(p)
                }
                if (abs(x - lastX) > 1f || abs(y - lastY) > 1f) {
                    removeCallbacks(p.scrubRepeat)
                    postDelayed(p.scrubRepeat, 420)
                }
            }
            Mode.GLIDE -> { addGlidePoint(p, x, y); invalidate() }
            Mode.NORMAL -> {
                if (key.type == KeyType.SPACE && config.spaceCursor && dist > unitW * 0.35f) {
                    p.mode = if (p.doubleTapSpace && config.spaceSelect) Mode.SELECT else Mode.CURSOR
                    if (p.mode == Mode.SELECT) listener.onSelectStart()
                    p.anchorX = x; p.anchorY = y
                    removeCallbacks(p.longPress)
                    invalidate()
                } else if ((key.type == KeyType.CHAR || key.type == KeyType.BACKSPACE) && config.swipeLeftDelete && config.swipeLeftChars &&
                    dx < -unitW * 0.5f && abs(dx) > abs(dy) * 1.3f
                ) {
                    p.mode = Mode.DELETE
                    removeCallbacks(p.longPress)
                    p.anchorX = p.downX - unitW * 0.5f
                    p.scrubGeo = 1
                    sendScrub(p)
                    postDelayed(p.scrubRepeat, 420)
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

    private fun sendScrub(p: Ptr) {
        val n = (p.scrubGeo + p.scrubExtra).coerceAtLeast(0)
        if (n != p.scrubSent) {
            p.scrubSent = n
            listener.onDeleteScrub(n)
        }
    }

    private fun addGlidePoint(p: Ptr, x: Float, y: Float) {
        if (p.glideX.isEmpty()) p.trailPath.moveTo(x, y) else p.trailPath.lineTo(x, y)
        p.glideX += toUnitX(x)
        p.glideY += toUnitY(y)
    }

    private fun onLongPress(p: Ptr) {
        val g = p.geom ?: return
        val key = g.key
        if (p.mode != Mode.NORMAL || p.moved) return
        when (key.type) {
            KeyType.CHAR -> {
                p.popupItems = key.alternates
                p.popupCellW = (unitW * 0.95f).coerceAtLeast(dp(38))
                val total = p.popupCellW * p.popupItems.size
                p.popupX = (g.cx - p.popupCellW / 2).coerceAtMost(width - total - dp(2)).coerceAtLeast(dp(2))
                p.popupY = (g.rect.top - rowH * 0.95f - dp(6)).coerceAtLeast(0f)
                p.popupSel = floor((p.x - p.popupX) / p.popupCellW).toInt().coerceIn(0, p.popupItems.size - 1)
                p.popupTime = SystemClock.uptimeMillis()
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
        removeCallbacks(p.scrubRepeat)
        val g = p.geom
        if (g == null) { invalidate(); return }
        val key = g.key
        released[key] = SystemClock.uptimeMillis()
        when (p.mode) {
            Mode.POPUP -> p.popupItems.getOrNull(p.popupSel)?.let {
                val (ux, uy) = unitPoint(g, p.downX, p.downY)
                listener.onText(it, key, ux, uy, false)
            }
            Mode.GLIDE -> {
                val distinct = p.glideX.indices.mapNotNullTo(LinkedHashSet()) { i ->
                    layout?.keyAt(p.glideX[i], p.glideY[i])
                }.count { it.isLetter }
                if (distinct >= 3) listener.onGlide(p.glideX.toFloatArray(), p.glideY.toFloatArray(), p.glideX.size)
                else tap(p, g)
                fadingTrail = p.trailPath
                trailFadeStart = SystemClock.uptimeMillis()
            }
            Mode.DELETE -> listener.onDeleteScrubEnd()
            Mode.SELECT -> listener.onSelectEnd()
            Mode.NORMAL -> {
                tap(p, g)
                if (key.type == KeyType.SPACE) lastSpaceTapUp = SystemClock.uptimeMillis()
            }
            Mode.CURSOR, Mode.REPEAT, Mode.DONE -> {}
        }
        invalidate()
    }

    private fun tap(p: Ptr, g: KeyGeom) {
        val key = g.key
        val gesture = GestureClassifier.classify((p.x - p.downX) / unitW, (p.y - p.downY) / unitW)
        val (ux, uy) = unitPoint(g, p.downX, p.downY)
        when (key.type) {
            KeyType.CHAR -> when {
                gesture == Gesture.SWIPE_UP && config.swipeUpCaps && key.isLetter -> listener.onText(key.label, key, ux, uy, true)
                gesture == Gesture.SWIPE_DOWN && config.swipeDownSymbol && key.secondary != null -> listener.onText(key.secondary!!, key, ux, uy, false)
                gesture == Gesture.SWIPE_LEFT && config.swipeLeftDelete ->
                    if (config.swipeLeftChars) { listener.onDeleteScrub(1); listener.onDeleteScrubEnd() } else listener.onDeleteWord()
                gesture == Gesture.SWIPE_RIGHT && config.swipeRightRestore -> listener.onRestoreWord()
                else -> listener.onText(key.label, key, ux, uy, false)
            }
            KeyType.SPACE -> listener.onAction(KeyType.SPACE)
            KeyType.BACKSPACE -> if (gesture == Gesture.SWIPE_LEFT) listener.onDeleteWord() else listener.onAction(KeyType.BACKSPACE)
            else -> listener.onAction(key.type)
        }
    }

    fun cancelAll() {
        for (p in ptrs.values) {
            removeCallbacks(p.longPress); removeCallbacks(p.repeat); removeCallbacks(p.scrubRepeat)
            if (p.mode == Mode.DELETE) listener.onDeleteScrubEnd()
            if (p.mode == Mode.SELECT) listener.onSelectEnd()
        }
        ptrs.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FADE_MS = 170f
    }
}
