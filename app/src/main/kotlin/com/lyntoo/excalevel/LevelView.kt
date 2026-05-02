package com.lyntoo.excalevel

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var deviation: Float = 0f
        set(value) { field = value; invalidate() }

    var toleranceDeg: Float = 2f
        set(value) { field = value; invalidate() }

    private val maxDeg = 20f

    // Strings cached at construction time — locale resolved once
    private val strFront       = context.getString(R.string.label_front)
    private val strBack        = context.getString(R.string.label_back)
    private val strLevel       = context.getString(R.string.status_perpendicular)
    private val strTiltForward = context.getString(R.string.direction_forward)
    private val strTiltBack    = context.getString(R.string.direction_backward)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111111") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF44")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 9f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val barTop = h * 0.18f
        val barBot = h * 0.68f
        val barMid = (barTop + barBot) / 2f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Color zones (red → yellow → green)
        fillPaint.color = Color.parseColor("#7A1A1A")
        canvas.drawRect(0f, barTop, w, barBot, fillPaint)

        val yellowW = (5f / maxDeg) * cx
        fillPaint.color = Color.parseColor("#7A5A00")
        canvas.drawRect(cx - yellowW, barTop, cx + yellowW, barBot, fillPaint)

        val greenW = (toleranceDeg / maxDeg) * cx
        fillPaint.color = Color.parseColor("#1A5A1A")
        canvas.drawRect(cx - greenW, barTop, cx + greenW, barBot, fillPaint)

        // Graduation marks
        textPaint.textSize = 26f
        textPaint.color = Color.parseColor("#888888")
        for (deg in floatArrayOf(-20f, -15f, -10f, -5f, 5f, 10f, 15f, 20f)) {
            val x = cx + (deg / maxDeg) * cx
            strokePaint.color = Color.parseColor("#555555")
            strokePaint.strokeWidth = 2f
            canvas.drawLine(x, barTop, x, barTop + 18f, strokePaint)
            canvas.drawLine(x, barBot - 18f, x, barBot, strokePaint)
            canvas.drawText("${deg.toInt()}°", x, barTop - 8f, textPaint)
        }
        // 0° graduation (prominent)
        textPaint.color = Color.parseColor("#00FF44")
        textPaint.textSize = 28f
        canvas.drawText("0°", cx, barTop - 8f, textPaint)

        // Center dashed line (90° reference)
        canvas.drawLine(cx, barTop - 5f, cx, barBot + 5f, dashPaint)

        // Bar border
        strokePaint.color = Color.parseColor("#444444")
        strokePaint.strokeWidth = 2f
        canvas.drawRect(0f, barTop, w, barBot, strokePaint)

        // FRONT / BACK labels inside bar
        textPaint.textSize = 32f
        textPaint.color = Color.parseColor("#666666")
        canvas.drawText(strFront, cx * 0.30f, barMid + 12f, textPaint)
        canvas.drawText(strBack,  cx * 1.70f, barMid + 12f, textPaint)

        // Moving indicator
        val clamped = deviation.coerceIn(-maxDeg, maxDeg)
        val indX = cx + (clamped / maxDeg) * cx
        val inZone = abs(deviation) <= toleranceDeg
        val inWarn = abs(deviation) <= 5f

        val indColor = when {
            inZone -> Color.parseColor("#00FF44")
            inWarn -> Color.parseColor("#FFAA00")
            else   -> Color.parseColor("#FF4444")
        }

        // Shadow for visibility
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = 16f
        canvas.drawLine(indX, barTop - 28f, indX, barBot + 28f, strokePaint)

        // Main indicator
        strokePaint.color = indColor
        strokePaint.strokeWidth = 10f
        canvas.drawLine(indX, barTop - 28f, indX, barBot + 28f, strokePaint)

        // Triangle pointer (above bar)
        val tri = Path()
        tri.moveTo(indX, barTop - 32f)
        tri.lineTo(indX - 20f, barTop - 58f)
        tri.lineTo(indX + 20f, barTop - 58f)
        tri.close()
        fillPaint.color = indColor
        canvas.drawPath(tri, fillPaint)

        // Status message (bottom)
        if (inZone) {
            textPaint.color = Color.parseColor("#00FF44")
            textPaint.textSize = 52f
            canvas.drawText(strLevel, cx, h * 0.88f, textPaint)
        } else {
            val dir = if (deviation < 0) strTiltForward else strTiltBack
            val deg = String.format("%.1f°", abs(deviation))
            textPaint.color = indColor
            textPaint.textSize = 44f
            canvas.drawText("$dir    $deg", cx, h * 0.88f, textPaint)
        }
    }
}
