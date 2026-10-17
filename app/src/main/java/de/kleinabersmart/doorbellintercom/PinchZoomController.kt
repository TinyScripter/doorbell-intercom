package de.kleinabersmart.doorbellintercom

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Pinch-to-Zoom fürs Live-Bild: zwei Finger zoomen um den Mittelpunkt der
 * Geste, ein Finger verschiebt das gezoomte Bild, Doppeltippen springt
 * zwischen Originalgröße und 2,5-fachem Zoom an der angetippten Stelle.
 *
 * Skaliert wird die Video-Surface des PlayerView. Das funktioniert nur mit
 * surface_type="texture_view" – eine SurfaceView ignoriert View-Transformationen.
 */
class PinchZoomController private constructor(private val playerView: PlayerView) {

    private var scale = 1f
    private var translationX = 0f
    private var translationY = 0f

    private val scaleDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scale <= MIN_SCALE) return false
                translationX -= distanceX
                translationY -= distanceY
                apply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > MIN_SCALE) {
                    reset()
                } else {
                    zoomBy(DOUBLE_TAP_SCALE, e.x, e.y)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.performClick()
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility") // performClick() in onSingleTapConfirmed
    private fun attach() {
        playerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
        // Ändert sich die Größe (Rotation, Buttonleiste, Seitenverhältnis des
        // Streams), die Verschiebung wieder in gültige Grenzen bringen.
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
    }

    /** Skaliert um [factor], wobei der Punkt ([focusX], [focusY]) im PlayerView an Ort und Stelle bleibt. */
    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val effective = newScale / scale
        // Die Surface ist im PlayerView zentriert und skaliert um ihre Mitte.
        val dx = focusX - playerView.width / 2f
        val dy = focusY - playerView.height / 2f
        translationX = dx * (1 - effective) + translationX * effective
        translationY = dy * (1 - effective) + translationY * effective
        scale = newScale
        apply()
    }

    private fun reset() {
        scale = MIN_SCALE
        translationX = 0f
        translationY = 0f
        apply()
    }

    @OptIn(UnstableApi::class) // PlayerView.videoSurfaceView
    private fun apply() {
        val target = playerView.videoSurfaceView ?: return
        // Nur so weit verschieben, dass kein Rand neben dem Bild entsteht;
        // ist das Bild in einer Richtung kleiner als der View, bleibt es mittig.
        val maxX = ((target.width * scale - playerView.width) / 2f).coerceAtLeast(0f)
        val maxY = ((target.height * scale - playerView.height) / 2f).coerceAtLeast(0f)
        translationX = translationX.coerceIn(-maxX, maxX)
        translationY = translationY.coerceIn(-maxY, maxY)

        target.scaleX = scale
        target.scaleY = scale
        target.translationX = translationX
        target.translationY = translationY
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f

        fun attach(playerView: PlayerView) {
            PinchZoomController(playerView).attach()
        }
    }
}
