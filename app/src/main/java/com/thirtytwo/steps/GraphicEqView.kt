package com.thirtytwo.steps

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class GraphicEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnGainChangeListener {
        fun onGainChanged(bandIndex: Int, gainDb: Float)
    }

    var listener: OnGainChangeListener? = null

    private val bandLabels = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    private val bandCount = 10
    private val gains = FloatArray(bandCount)

    private val minDb = -12f
    private val maxDb = 12f

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.5f)
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        textAlign = Paint.Align.RIGHT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val dbValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val freqLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    // Layout
    private val cardPadding = dp(12f)
    private val leftPad = dp(32f)
    private val rightPad = dp(14f)
    private val topPad = dp(20f)
    private val bottomPad = dp(20f)
    private val dotRadius = dp(7.5f)
    private val barWidth = dp(8f)
    private val cardRadius = dp(16f)

    // Touch + D-pad
    private var draggingBand = -1
    private var selectedBand = -1 // for D-pad navigation
    private var lastHapticStep = Int.MIN_VALUE
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }

    // Reusable
    private val curvePath = Path()
    private val fillPath = Path()
    private val cardRect = RectF()
    private val bandX = FloatArray(bandCount)
    private val bandY = FloatArray(bandCount)

    // Chart area offsets (relative to view)
    private var chartLeft = 0f
    private var chartRight = 0f
    private var chartTop = 0f
    private var chartBottom = 0f

    init {
        val accent = resolveColor(com.google.android.material.R.attr.colorPrimary, 0xFF80CBC4.toInt())
        val onSurface = resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val surfaceVariant = resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF2C2C2C.toInt())

        cardPaint.color = surfaceVariant
        gridLinePaint.color = withAlpha(onSurface, 20)
        zeroLinePaint.color = withAlpha(onSurface, 45)
        gridTextPaint.color = withAlpha(onSurface, 80)
        barPaint.color = withAlpha(accent, 120)
        curvePaint.color = accent
        fillPaint.color = withAlpha(accent, 25)
        dotPaint.color = accent
        dotRingPaint.color = withAlpha(accent, 180)
        dbValuePaint.color = accent
        freqLabelPaint.color = withAlpha(onSurface, 150)
        selectedRingPaint.color = Color.WHITE
    }

    fun setGains(newGains: FloatArray) {
        for (i in 0 until bandCount.coerceAtMost(newGains.size)) {
            gains[i] = newGains[i].coerceIn(minDb, maxDb)
        }
        invalidate()
    }

    fun getGains(): FloatArray = gains.copyOf()

    fun setGain(index: Int, db: Float) {
        if (index in 0 until bandCount) {
            gains[index] = db.coerceIn(minDb, maxDb)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Card background
        cardRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        // Chart area
        chartLeft = cardPadding + leftPad
        chartRight = width - cardPadding - rightPad
        chartTop = cardPadding + topPad
        chartBottom = height - cardPadding - bottomPad
        val cW = chartRight - chartLeft
        val cH = chartBottom - chartTop

        if (cW <= 0 || cH <= 0) return

        // Band positions
        val spacing = cW / (bandCount - 1)
        for (i in 0 until bandCount) {
            bandX[i] = chartLeft + i * spacing
            bandY[i] = dbToY(gains[i])
        }

        // Grid lines
        val dbSteps = floatArrayOf(-10f, -5f, 0f, 5f, 10f)
        for (db in dbSteps) {
            val y = dbToY(db)
            val paint = if (db == 0f) zeroLinePaint else gridLinePaint
            canvas.drawLine(chartLeft, y, chartRight, y, paint)

            val label = when {
                db > 0 -> "+${db.toInt()}"
                db == 0f -> "0"
                else -> "${db.toInt()}"
            }
            canvas.drawText(label, chartLeft - dp(5f), y + gridTextPaint.textSize * 0.35f, gridTextPaint)
        }

        // Vertical bars (filled rectangles from zero line to dot)
        val zeroY = dbToY(0f)
        for (i in 0 until bandCount) {
            val halfBar = barWidth / 2f
            val top = minOf(bandY[i], zeroY)
            val bottom = maxOf(bandY[i], zeroY)
            canvas.drawRoundRect(
                bandX[i] - halfBar, top,
                bandX[i] + halfBar, bottom,
                halfBar, halfBar, barPaint
            )
        }

        // Fill from chart bottom to curve (Wavelet-style contour fill)
        fillPath.reset()
        fillPath.moveTo(bandX[0], chartBottom)
        for (i in 0 until bandCount) {
            fillPath.lineTo(bandX[i], bandY[i])
        }
        fillPath.lineTo(bandX[bandCount - 1], chartBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Curve line
        curvePath.reset()
        curvePath.moveTo(bandX[0], bandY[0])
        for (i in 1 until bandCount) {
            curvePath.lineTo(bandX[i], bandY[i])
        }
        canvas.drawPath(curvePath, curvePaint)

        // Dots + labels
        for (i in 0 until bandCount) {
            val x = bandX[i]
            val y = bandY[i]

            // Dot with ring (+ highlight if D-pad selected)
            canvas.drawCircle(x, y, dotRadius, dotPaint)
            canvas.drawCircle(x, y, dotRadius + dp(1f), dotRingPaint)
            if (i == selectedBand) {
                canvas.drawCircle(x, y, dotRadius + dp(5f), selectedRingPaint)
            }

            // dB value above/below dot depending on position
            val dbText = String.format("%.1f", gains[i])
            val above = y > chartTop + dp(18f)
            val textY = if (above) y - dotRadius - dp(6f) else y + dotRadius + dp(14f)
            canvas.drawText(dbText, x, textY, dbValuePaint)

            // Frequency label
            canvas.drawText(bandLabels[i], x, chartBottom + bottomPad - dp(2f), freqLabelPaint)
        }
    }

    private fun dbToY(db: Float): Float {
        return chartTop + (maxDb - db) / (maxDb - minDb) * (chartBottom - chartTop)
    }

    private fun yToDb(y: Float): Float {
        return maxDb - (y - chartTop) / (chartBottom - chartTop) * (maxDb - minDb)
    }

    // --- Touch ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (chartBottom <= chartTop) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingBand = findNearestBand(event.x)
                if (draggingBand >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    lastHapticStep = gainToStep(gains[draggingBand])
                    updateDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingBand >= 0) {
                    updateDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingBand >= 0) {
                    draggingBand = -1
                    lastHapticStep = Int.MIN_VALUE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return false
    }

    // --- D-pad / remote control ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedBand <= 0) {
                    // At first band or no selection - let focus leave the view
                    selectedBand = -1
                    invalidate()
                    return false
                }
                selectedBand = selectedBand - 1
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedBand >= bandCount - 1) {
                    // At last band - let focus leave the view
                    selectedBand = -1
                    invalidate()
                    return false
                }
                if (selectedBand < 0) selectedBand = 0
                else selectedBand = selectedBand + 1
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedBand >= 0) {
                    val newGain = (gains[selectedBand] + 0.5f).coerceAtMost(maxDb)
                    gains[selectedBand] = newGain
                    listener?.onGainChanged(selectedBand, newGain)
                    invalidate()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedBand >= 0) {
                    val newGain = (gains[selectedBand] - 0.5f).coerceAtLeast(minDb)
                    gains[selectedBand] = newGain
                    listener?.onGainChanged(selectedBand, newGain)
                    invalidate()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (selectedBand < 0) selectedBand = 0
                else {
                    // Reset selected band to 0
                    gains[selectedBand] = 0f
                    listener?.onGainChanged(selectedBand, 0f)
                }
                invalidate()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun findNearestBand(x: Float): Int {
        if (bandCount == 0) return -1
        val spacing = (chartRight - chartLeft) / (bandCount - 1)
        val touchZone = spacing * 0.6f

        var best = -1
        var bestDist = touchZone
        for (i in 0 until bandCount) {
            val dist = kotlin.math.abs(x - bandX[i])
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    private fun updateDrag(y: Float) {
        val db = yToDb(y).coerceIn(minDb, maxDb)
        val rounded = Math.round(db * 10f) / 10f
        gains[draggingBand] = rounded
        listener?.onGainChanged(draggingBand, rounded)

        // Haptic tick every 0.5 dB
        val step = gainToStep(rounded)
        if (step != lastHapticStep) {
            lastHapticStep = step
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            } else {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        invalidate()
    }

    private fun gainToStep(db: Float): Int = (db * 2f).toInt() // step per 0.5 dB

    // --- Helpers ---

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
