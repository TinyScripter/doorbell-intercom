package de.kleinabersmart.doorbellintercom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

/**
 * "Zum Öffnen schieben": Bestätigung für "Tür öffnen", damit ein versehentlicher Tap
 * (Hosentasche, Kind) nicht die Haustür öffnet. Der Tür-Button blendet den Slider nur ein
 * ([toggle]); ausgelöst wird erst, wenn man den Knopf bis ans Ende zieht. Ohne Bedienung
 * verschwindet er nach [AUTO_HIDE_MS] wieder.
 *
 * Für TalkBack gilt der Doppeltipp (Klick) als Bestätigung – Wischen ist dort kaum möglich,
 * und der Doppeltipp ist schon eine bewusste Aktion.
 */
class SlideToConfirmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackInset = 4 * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.slider_track)
    }
    private val trackOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.slider_track_outline)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_video)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val thumbBackground: Drawable = ContextCompat.getDrawable(context, R.drawable.bg_btn_door)!!
    private val thumbIcon: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_door)!!
    private val trackRect = RectF()

    private var label: CharSequence = ""
    private var onConfirmed: (() -> Unit)? = null

    /** Position des Knopfs in Pixeln ab dem linken Anschlag. */
    private var thumbOffset = 0f
    private var dragging = false
    private var downX = 0f
    private var downOffset = 0f
    private var animator: ValueAnimator? = null
    private val autoHide = Runnable { hide() }

    init {
        isClickable = true
        isFocusable = true
        isVisible = false
    }

    /**
     * Blendet den Slider mit [label] ein, [onConfirmed] läuft nach dem Schieben. Ist er für
     * dasselbe Label schon sichtbar, verschwindet er stattdessen (zweiter Tap = abbrechen).
     * [actionLabel] nennt TalkBack als Aktion beim Doppeltipp.
     */
    fun toggle(label: CharSequence, actionLabel: CharSequence, onConfirmed: () -> Unit) {
        if (isVisible && label == this.label) {
            hide()
            return
        }
        this.label = label
        this.onConfirmed = onConfirmed
        contentDescription = label
        ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, actionLabel, null)
        resetThumb()
        isVisible = true
        scheduleAutoHide()
    }

    fun hide() {
        removeCallbacks(autoHide)
        animator?.cancel()
        dragging = false
        onConfirmed = null
        isVisible = false
    }

    private fun resetThumb() {
        animator?.cancel()
        thumbOffset = 0f
        invalidate()
    }

    private fun scheduleAutoHide() {
        removeCallbacks(autoHide)
        postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private val thumbSize: Float get() = height - 2 * trackInset
    private val maxOffset: Float get() = (width - 2 * trackInset - thumbSize).coerceAtLeast(0f)

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        trackRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        trackRect.inset(density / 2, density / 2)
        canvas.drawRoundRect(trackRect, radius, radius, trackOutlinePaint)

        // Text mittig im freien Teil rechts vom Knopf; verblasst beim Schieben.
        val thumbStart = trackInset + thumbOffset
        val textStart = thumbStart + thumbSize
        val textEnd = width - trackInset
        val available = textEnd - textStart - 2 * trackInset
        if (available > 0) {
            val text = TextUtils.ellipsize(label, textPaint, available, TextUtils.TruncateAt.END)
            textPaint.alpha = (255 * (1f - 2 * progress)).toInt().coerceIn(0, 255)
            val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, 0, text.length, (textStart + textEnd) / 2, baseline, textPaint)
        }

        val left = thumbStart.toInt()
        val top = trackInset.toInt()
        val size = thumbSize.toInt()
        thumbBackground.setBounds(left, top, left + size, top + size)
        thumbBackground.draw(canvas)
        // Gleiches Verhältnis wie bei den runden Buttons (17dp Rand bei 88dp).
        val iconInset = size * 17 / 88
        thumbIcon.setBounds(left + iconInset, top + iconInset, left + size - iconInset, top + size - iconInset)
        thumbIcon.draw(canvas)
    }

    private val progress: Float get() = if (maxOffset > 0) thumbOffset / maxOffset else 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nur am Knopf greifen; ein Tap auf die Leiste soll nichts auslösen.
                val thumbStart = trackInset + thumbOffset
                if (event.x < thumbStart - trackInset || event.x > thumbStart + thumbSize + trackInset) return false
                animator?.cancel()
                dragging = true
                downX = event.x
                downOffset = thumbOffset
                removeCallbacks(autoHide)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                thumbOffset = (downOffset + event.x - downX).coerceIn(0f, maxOffset)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                if (progress >= CONFIRM_THRESHOLD) {
                    performClick()
                } else {
                    animateThumbBack()
                    scheduleAutoHide()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                animateThumbBack()
                scheduleAutoHide()
                return true
            }
        }
        return false
    }

    /** Ans Ende geschoben oder Doppeltipp mit TalkBack: bestätigt. */
    override fun performClick(): Boolean {
        super.performClick()
        val action = onConfirmed ?: return false
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
        hide()
        action()
        return true
    }

    /** Nicht weit genug geschoben: der Knopf schnappt zurück. */
    private fun animateThumbBack() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbOffset, 0f).apply {
            duration = 200
            addUpdateListener {
                thumbOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoHide)
        animator?.cancel()
    }

    internal companion object {
        /** Ab diesem Anteil des Wegs gilt das Schieben als bestätigt. */
        const val CONFIRM_THRESHOLD = 0.9f
        const val AUTO_HIDE_MS = 6000L
    }
}
