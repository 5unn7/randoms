package app.foldboard.core.gesture

import kotlin.math.abs

enum class Gesture { TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT }

object GestureClassifier {
    /**
     * Classify a single-key stroke. dx/dy are in key widths (y already converted from rows).
     * A short stroke is a tap; otherwise the dominant axis wins.
     */
    fun classify(dx: Float, dy: Float, threshold: Float = 0.55f): Gesture {
        if (abs(dx) < threshold && abs(dy) < threshold) return Gesture.TAP
        return if (abs(dx) >= abs(dy)) {
            if (dx < 0) Gesture.SWIPE_LEFT else Gesture.SWIPE_RIGHT
        } else {
            if (dy < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
        }
    }
}
