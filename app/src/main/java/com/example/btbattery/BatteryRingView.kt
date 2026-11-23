package com.example.btbattery

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class BatteryRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var level: Int = -1

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        color = ContextCompat.getColor(context, R.color.ring_track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 96f
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 32f
    }

    private val rect = RectF()

    fun setBatteryLevel(newLevel: Int) {
        level = newLevel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 28f
        rect.set(pad, pad, width - pad, height - pad)

        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        if (level in 0..100) {
            progressPaint.color = when {
                level <= 20 -> ContextCompat.getColor(context, R.color.status_red)
                level <= 45 -> ContextCompat.getColor(context, R.color.status_orange)
                else -> ContextCompat.getColor(context, R.color.status_green)
            }
            val sweep = 360f * (level / 100f)
            canvas.drawArc(rect, -90f, sweep, false, progressPaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        val mainText = if (level in 0..100) "$level%" else "--"
        canvas.drawText(mainText, cx, cy + 20f, textPaint)
        val subText = if (level in 0..100) "当前电量" else "等待连接"
        canvas.drawText(subText, cx, cy + 66f, subPaint)
    }
}
