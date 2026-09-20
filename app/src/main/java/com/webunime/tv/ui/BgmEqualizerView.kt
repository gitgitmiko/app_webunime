package com.webunime.tv.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.webunime.tv.R

/** Indikator equalizer kecil di samping judul BGM. */
class BgmEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barCount = 3
    private val levels = FloatArray(barCount) { 0.35f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.wu_accent_soft)
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var animatorSet: AnimatorSet? = null
    private var running = false

    fun setAnimating(active: Boolean) {
        if (active) start() else stop()
    }

    fun start() {
        if (running) return
        running = true
        val animators = ArrayList<Animator>(barCount)
        val peaks = floatArrayOf(0.95f, 0.55f, 0.85f)
        val troughs = floatArrayOf(0.25f, 0.35f, 0.2f)
        val durations = longArrayOf(420L, 560L, 480L)
        for (i in 0 until barCount) {
            val anim = ValueAnimator.ofFloat(troughs[i], peaks[i]).apply {
                duration = durations[i]
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = (i * 90L)
                addUpdateListener { va ->
                    levels[i] = va.animatedValue as Float
                    invalidate()
                }
            }
            animators += anim
        }
        animatorSet = AnimatorSet().also {
            it.playTogether(animators)
            it.start()
        }
    }

    fun stop() {
        running = false
        animatorSet?.cancel()
        animatorSet = null
        for (i in levels.indices) levels[i] = 0.22f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.14f
        val barW = (w - gap * (barCount - 1)) / barCount
        val radius = barW * 0.45f
        for (i in 0 until barCount) {
            val barH = (h * levels[i]).coerceIn(h * 0.15f, h)
            val left = i * (barW + gap)
            rect.set(left, h - barH, left + barW, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
