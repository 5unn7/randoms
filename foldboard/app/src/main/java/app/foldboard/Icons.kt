package app.foldboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Hand-drawn key icons (no font glyph dependencies, so they look the same on every device).
 * Each draws centered at (cx, cy) inside a box of size [s] using a stroke paint.
 */
object Icons {
    private val path = Path()
    private val rect = RectF()

    private fun stroke(p: Paint, s: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = (s * 0.085f).coerceAtLeast(2f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, filled: Boolean, locked: Boolean) {
        stroke(p, s)
        val h = s * 0.5f
        path.reset()
        path.moveTo(cx, cy - h)
        path.lineTo(cx + h * 0.9f, cy + h * 0.05f)
        path.lineTo(cx + h * 0.42f, cy + h * 0.05f)
        path.lineTo(cx + h * 0.42f, cy + h * 0.62f)
        path.lineTo(cx - h * 0.42f, cy + h * 0.62f)
        path.lineTo(cx - h * 0.42f, cy + h * 0.05f)
        path.lineTo(cx - h * 0.9f, cy + h * 0.05f)
        path.close()
        if (filled) { p.style = Paint.Style.FILL_AND_STROKE }
        c.drawPath(path, p)
        if (locked) {
            p.style = Paint.Style.STROKE
            c.drawLine(cx - h * 0.42f, cy + h * 0.95f, cx + h * 0.42f, cy + h * 0.95f, p)
        }
        p.style = Paint.Style.STROKE
    }

    fun backspace(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val w = s * 0.55f; val h = s * 0.36f
        path.reset()
        path.moveTo(cx - w, cy)
        path.lineTo(cx - w * 0.45f, cy - h)
        path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h)
        path.lineTo(cx - w * 0.45f, cy + h)
        path.close()
        c.drawPath(path, p)
        val x = cx + w * 0.28f; val d = h * 0.45f
        c.drawLine(x - d, cy - d, x + d, cy + d, p)
        c.drawLine(x - d, cy + d, x + d, cy - d, p)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val w = s * 0.45f; val h = s * 0.32f
        path.reset()
        path.moveTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h * 0.35f)
        path.lineTo(cx - w, cy + h * 0.35f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx - w * 0.45f, cy - h * 0.25f)
        path.lineTo(cx - w, cy + h * 0.35f)
        path.lineTo(cx - w * 0.45f, cy + h * 0.95f)
        c.drawPath(path, p)
    }

    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, active: Boolean = false) {
        stroke(p, s)
        val w = s * 0.17f; val h = s * 0.3f
        rect.set(cx - w, cy - h * 1.35f, cx + w, cy + h * 0.25f)
        if (active) p.style = Paint.Style.FILL_AND_STROKE
        c.drawRoundRect(rect, w, w, p)
        p.style = Paint.Style.STROKE
        rect.set(cx - w * 2f, cy - h * 0.9f, cx + w * 2f, cy + h * 0.75f)
        c.drawArc(rect, 0f, 180f, false, p)
        c.drawLine(cx, cy + h * 0.75f, cx, cy + h * 1.25f, p)
    }

    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val r = s * 0.4f
        c.drawCircle(cx, cy, r, p)
        rect.set(cx - r * 0.55f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.55f)
        c.drawArc(rect, 20f, 140f, false, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx - r * 0.35f, cy - r * 0.25f, p.strokeWidth * 0.7f, p)
        c.drawCircle(cx + r * 0.35f, cy - r * 0.25f, p.strokeWidth * 0.7f, p)
        p.style = Paint.Style.STROKE
    }

    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val w = s * 0.3f; val h = s * 0.4f
        rect.set(cx - w, cy - h * 0.8f, cx + w, cy + h)
        c.drawRoundRect(rect, w * 0.25f, w * 0.25f, p)
        rect.set(cx - w * 0.45f, cy - h * 1.05f, cx + w * 0.45f, cy - h * 0.6f)
        c.drawRoundRect(rect, w * 0.15f, w * 0.15f, p)
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, p)
        c.drawLine(cx - w * 0.5f, cy + h * 0.35f, cx + w * 0.3f, cy + h * 0.35f, p)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val r = s * 0.24f
        c.drawCircle(cx, cy, r * 0.55f, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = cx + Math.cos(a).toFloat() * r; val y1 = cy + Math.sin(a).toFloat() * r
            val x2 = cx + Math.cos(a).toFloat() * r * 1.55f; val y2 = cy + Math.sin(a).toFloat() * r * 1.55f
            c.drawLine(x1, y1, x2, y2, p)
        }
        c.drawCircle(cx, cy, r, p)
    }

    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val w = s * 0.3f
        path.reset()
        path.moveTo(cx - w, cy - w * 0.4f)
        path.lineTo(cx, cy + w * 0.5f)
        path.lineTo(cx + w, cy - w * 0.4f)
        c.drawPath(path, p)
    }

    /** Four-way arrows (move). */
    fun move(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val r = s * 0.36f; val a = s * 0.11f
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            val tx = cx + dx * r; val ty = cy + dy * r
            path.reset()
            path.moveTo(tx - dx * a - dy * a, ty - dy * a - dx * a)
            path.lineTo(tx, ty)
            path.lineTo(tx - dx * a + dy * a, ty - dy * a + dx * a)
            c.drawPath(path, p)
        }
    }

    /** Resize: a frame with a diagonal double arrow. */
    fun resize(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val r = s * 0.34f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawRoundRect(rect, r * 0.25f, r * 0.25f, p)
        val d = r * 0.5f; val a = r * 0.3f
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
        c.drawLine(cx + d, cy - d, cx + d - a, cy - d, p); c.drawLine(cx + d, cy - d, cx + d, cy - d + a, p)
        c.drawLine(cx - d, cy + d, cx - d + a, cy + d, p); c.drawLine(cx - d, cy + d, cx - d, cy + d - a, p)
    }

    /** 2x2 grid of dots: opens the toolbar. */
    fun grid(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        p.style = Paint.Style.FILL
        val o = s * 0.17f; val r = s * 0.075f
        for (dx in listOf(-o, o)) for (dy in listOf(-o, o)) c.drawCircle(cx + dx, cy + dy, r, p)
        p.style = Paint.Style.STROKE
    }

    fun close(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        stroke(p, s)
        val d = s * 0.2f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p)
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
    }
}
