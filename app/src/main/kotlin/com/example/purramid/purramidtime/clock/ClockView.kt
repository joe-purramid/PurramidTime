// ClockView.kt
package com.example.purramid.purramidtime.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.ui.PurramidPalette
import com.example.purramid.purramidtime.util.dpToPx
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A custom view that displays a clock, either analog or digital.
 * This view does NOT manage its own time state. It relies on external updates
 * via `updateDisplayTime` and configuration via setter methods.
 * It reports user interactions (dragging hands) back via ClockInteractionListener.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Listener for Interactions ---
    interface ClockInteractionListener {
        /** Called when the user finishes dragging a hand, providing the calculated time. */
        fun onTimeManuallySet(instanceId: Int, newTime: LocalTime)

        /** Called when the user starts (isDragging=true) or stops (isDragging=false) dragging a hand. */
        fun onDragStateChanged(instanceId: Int, isDragging: Boolean)
    }

    var interactionListener: ClockInteractionListener? = null

    // --- Configuration State (Configurable via setters) ---
    private var instanceId: Int = -1
    private var isAnalog: Boolean = true // Default to analog
    private var clockColor: Int = Color.WHITE
    private var is24Hour: Boolean = false
    private var displaySeconds: Boolean = true
    private var isPaused: Boolean = false
    private var timeZoneId: ZoneId = ZoneId.systemDefault()

    // --- State for Display ---
    private var displayedTime: LocalTime = LocalTime.now()

    // --- Drawing Properties ---
    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var radius: Float = 0f

    // Clock face drawable (optional)
    private var clockFaceDrawable: Drawable? = null

    // --- Touch Handling State ---
    private enum class ClockHand { HOUR, MINUTE, SECOND }
    private var draggedHand: ClockHand? = null
    private var lastTouchAngle: Float = 0f
    private var dragStartTime: LocalTime? = null

    // --- Drawing Tools ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics)
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private lateinit var timeFormatter: DateTimeFormatter
    private val bounds = Rect()

    // --- Initialization ---
    init {
        // Initialize formatter
        updateTimeFormat()
        setPaintColors()

        // Load the clock face drawable if available
        clockFaceDrawable = ContextCompat.getDrawable(context, R.drawable.clock_face)
    }

    // --- Size Management ---
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Calculate center and radius
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f * 0.9f // 90% of available space
    }

    // --- Public Configuration Methods ---

    fun setInstanceId(id: Int) {
        this.instanceId = id
    }

    fun setClockMode(isAnalogMode: Boolean) {
        if (isAnalog != isAnalogMode) {
            isAnalog = isAnalogMode
            invalidate()
        }
    }

    fun setClockColor(color: Int) {
        if (clockColor != color) {
            clockColor = color
            setPaintColors()
            invalidate()
        }
    }

    fun setIs24HourFormat(is24: Boolean) {
        if (is24Hour != is24) {
            is24Hour = is24
            updateTimeFormat()
            invalidate()
        }
    }

    fun setClockTimeZone(zoneId: ZoneId) {
        if (this.timeZoneId != zoneId) {
            this.timeZoneId = zoneId
            updateTimeFormat()
            invalidate()
        }
    }

    fun setDisplaySeconds(display: Boolean) {
        if (displaySeconds != display) {
            displaySeconds = display
            updateTimeFormat()
            invalidate()
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        invalidate()
    }

    /**
     * Updates the time displayed by the clock. Call this externally (e.g., from Service observing ViewModel).
     * @param timeToDisplay The LocalTime to display.
     */
    fun updateDisplayTime(timeToDisplay: LocalTime) {
        this.displayedTime = timeToDisplay
        invalidate()
    }

    // --- Internal Helper Methods ---

    private fun setPaintColors() {
        val contrastColor = getContrastColor(clockColor)
        textPaint.color = contrastColor
        handPaint.color = contrastColor
        numberPaint.color = contrastColor
    }

    private fun getContrastColor(backgroundColor: Int): Int {
        // Use the PurramidPalette color mapping
        return when (backgroundColor) {
            PurramidPalette.WHITE.colorInt,
            PurramidPalette.GOLDENROD.colorInt,
            PurramidPalette.LIGHT_BLUE.colorInt -> Color.BLACK

            PurramidPalette.BLACK.colorInt,
            PurramidPalette.TEAL.colorInt,
            PurramidPalette.VIOLET.colorInt -> Color.WHITE

            else -> {
                // Fallback luminance calculation
                val r = Color.red(backgroundColor) / 255.0
                val g = Color.green(backgroundColor) / 255.0
                val b = Color.blue(backgroundColor) / 255.0
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                if (luminance > 0.5) Color.BLACK else Color.WHITE
            }
        }
    }

    private fun updateTimeFormat() {
        val locale = Locale.getDefault()
        val basePattern = if (is24Hour) "HH:mm" else "hh:mm"
        val patternWithSeconds = if (is24Hour) "HH:mm:ss" else "hh:mm:ss"
        timeFormatter = DateTimeFormatter.ofPattern(
            if (displaySeconds) patternWithSeconds else basePattern,
            locale
        )
    }

    // --- Main Drawing Method ---
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawColor(clockColor)

        if (isAnalog) {
            drawAnalogClock(canvas)
        } else {
            drawDigitalClock(canvas)
        }
    }

    // --- Analog Clock Drawing ---
    private fun drawAnalogClock(canvas: Canvas) {
        // Draw clock face (circle and tick marks)
        drawClockFace(canvas)

        // Draw numbers
        drawClockNumbers(canvas)

        // Draw hands
        drawHands(canvas)

        // Draw center dot
        handPaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, radius * 0.03f, handPaint)
        handPaint.style = Paint.Style.STROKE
    }

    private fun drawClockFace(canvas: Canvas) {
        // Draw outer circle
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.02f
            color = getContrastColor(clockColor)
        }
        canvas.drawCircle(centerX, centerY, radius, circlePaint)

        // Draw tick marks
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getContrastColor(clockColor)
            strokeCap = Paint.Cap.ROUND
        }

        for (i in 0 until 60) {
            val angle = i * 6f - 90f // 6 degrees per tick
            val isHourMark = i % 5 == 0

            // Set tick length and width
            val tickLength = if (isHourMark) radius * 0.1f else radius * 0.05f
            tickPaint.strokeWidth = if (isHourMark) radius * 0.02f else radius * 0.01f

            // Calculate tick positions
            val outerRadius = radius - radius * 0.05f
            val innerRadius = outerRadius - tickLength

            val cosAngle = cos(Math.toRadians(angle.toDouble())).toFloat()
            val sinAngle = sin(Math.toRadians(angle.toDouble())).toFloat()

            val x1 = centerX + outerRadius * cosAngle
            val y1 = centerY + outerRadius * sinAngle
            val x2 = centerX + innerRadius * cosAngle
            val y2 = centerY + innerRadius * sinAngle

            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }

    private fun drawClockNumbers(canvas: Canvas) {
        numberPaint.textSize = radius * 0.15f // Scale with clock size

        // Calculate text baseline offset
        val textBounds = Rect()
        numberPaint.getTextBounds("12", 0, 2, textBounds)
        val textHeight = textBounds.height()

        // Draw 12-hour numbers
        for (number in 1..12) {
            val angle = Math.toRadians((number * 30 - 90).toDouble())
            val numberRadius = radius * 0.75f // Position numbers at 75% of radius

            val x = centerX + (numberRadius * cos(angle)).toFloat()
            val y = centerY + (numberRadius * sin(angle)).toFloat() + textHeight / 2f

            canvas.drawText(number.toString(), x, y, numberPaint)
        }

        // Draw 24-hour numbers if enabled
        if (is24Hour) {
            numberPaint.textSize = radius * 0.10f // Smaller size for 24-hour numbers
            val savedAlpha = numberPaint.alpha
            numberPaint.alpha = (savedAlpha * 0.7f).toInt() // Slightly transparent

            for (number in 13..24) {
                val displayNumber = if (number == 24) "00" else number.toString()
                val hourNumber = if (number == 24) 12 else number - 12
                val angle = Math.toRadians((hourNumber * 30 - 90).toDouble())
                val numberRadius = radius * 0.55f // Inner circle for 24-hour numbers

                val x = centerX + (numberRadius * cos(angle)).toFloat()
                val y = centerY + (numberRadius * sin(angle)).toFloat() + textHeight / 2f

                canvas.drawText(displayNumber, x, y, numberPaint)
            }

            numberPaint.alpha = savedAlpha // Restore original alpha
        }
    }

    private fun drawHands(canvas: Canvas) {
        val time = displayedTime

        // Calculate angles for each hand
        val hours = if (is24Hour) time.hour else time.hour % 12
        val minutes = time.minute
        val seconds = time.second

        // Calculate rotation angles (0° is at 12 o'clock, clockwise)
        val hourAngle = (hours + minutes / 60f) * 30f - 90f // 30° per hour
        val minuteAngle = (minutes + seconds / 60f) * 6f - 90f // 6° per minute
        val secondAngle = seconds * 6f - 90f // 6° per second

        // Draw hour hand
        drawHand(
            canvas = canvas,
            angle = hourAngle,
            length = radius * 0.5f, // 50% of radius
            width = radius * 0.02f, // 2% of radius for thickness
            paint = handPaint
        )

        // Draw minute hand
        drawHand(
            canvas = canvas,
            angle = minuteAngle,
            length = radius * 0.7f, // 70% of radius
            width = radius * 0.015f, // 1.5% of radius for thickness
            paint = handPaint
        )

        // Draw second hand (if enabled)
        if (displaySeconds) {
            val secondPaint = Paint(handPaint).apply {
                color = Color.RED // Second hand is typically red
            }
            drawHand(
                canvas = canvas,
                angle = secondAngle,
                length = radius * 0.8f, // 80% of radius
                width = radius * 0.01f, // 1% of radius for thickness
                paint = secondPaint
            )
        }
    }

    private fun drawHand(
        canvas: Canvas,
        angle: Float,
        length: Float,
        width: Float,
        paint: Paint
    ) {
        canvas.save()
        canvas.rotate(angle, centerX, centerY)

        paint.strokeWidth = width

        // Draw the main hand
        canvas.drawLine(
            centerX,
            centerY,
            centerX,
            centerY - length,
            paint
        )

        // Draw a tail for balance
        val tailLength = length * 0.2f
        canvas.drawLine(
            centerX,
            centerY,
            centerX,
            centerY + tailLength,
            paint
        )

        canvas.restore()
    }

    // --- Digital Clock Drawing ---
    private fun drawDigitalClock(canvas: Canvas) {
        // Format the time
        val formattedTime = try {
            displayedTime.format(timeFormatter)
        } catch (e: Exception) {
            Log.e("ClockView", "Error formatting time: $displayedTime", e)
            "--:--"
        }

        // Adjust text size based on available width
        val availableWidth = width - paddingLeft - paddingRight
        textPaint.textSize = getDigitalTextSize(availableWidth)

        // Calculate position to draw centered text
        textPaint.getTextBounds(formattedTime, 0, formattedTime.length, bounds)
        val x = width / 2f
        val y = height / 2f - bounds.exactCenterY()

        // Draw the main time string
        canvas.drawText(formattedTime, x, y, textPaint)

        // Draw AM/PM indicator if needed
        if (!is24Hour) {
            val amPmFormatter = DateTimeFormatter.ofPattern("a", Locale.getDefault())
            val amPmString = try {
                displayedTime.format(amPmFormatter)
            } catch (e: Exception) {
                ""
            }

            val amPmPaint = Paint(textPaint).apply {
                textSize *= 0.4f // Smaller size for AM/PM
            }

            val mainTextWidth = textPaint.measureText(formattedTime)
            val amPmTextWidth = amPmPaint.measureText(amPmString)
            val padding = context.dpToPx(4)

            // Position AM/PM to the right of the main time
            val amPmX = x + (mainTextWidth / 2f) + (amPmTextWidth / 2f) + padding
            val amPmY = y

            canvas.drawText(amPmString, amPmX, amPmY, amPmPaint)
        }
    }

    private fun getDigitalTextSize(availableWidth: Int): Float {
        var textSize = min(width, height) * 0.4f
        val minSize = context.dpToPx(12).toFloat()

        textPaint.textSize = textSize
        val sampleText = if (displaySeconds) "00:00:00" else "00:00"
        var textWidth = textPaint.measureText(sampleText)
        val maxWidth = availableWidth * 0.9f

        while (textWidth > maxWidth && textSize > minSize) {
            textSize *= 0.95f
            textPaint.textSize = textSize
            textWidth = textPaint.measureText(sampleText)
        }

        return max(textSize, minSize)
    }

    // --- Touch Handling for Analog Clock ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only handle touch in analog mode when paused
        if (!isAnalog || !isPaused || interactionListener == null || instanceId == -1) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchAngle = calculateAngle(centerX, centerY, event.x, event.y)
                val distance = hypot(event.x - centerX, event.y - centerY)

                // Detect which hand is being touched
                draggedHand = detectHandTouch(event.x, event.y, distance)

                if (draggedHand != null) {
                    lastTouchAngle = touchAngle
                    dragStartTime = displayedTime
                    interactionListener?.onDragStateChanged(instanceId, true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                draggedHand?.let { hand ->
                    val currentAngle = calculateAngle(centerX, centerY, event.x, event.y)
                    val angleDelta = angleDifference(currentAngle, lastTouchAngle)

                    // Update the time based on the dragged hand
                    val newTime = calculateNewTime(hand, angleDelta)
                    updateDisplayTime(newTime)

                    lastTouchAngle = currentAngle
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedHand?.let {
                    interactionListener?.onTimeManuallySet(instanceId, displayedTime)
                    interactionListener?.onDragStateChanged(instanceId, false)
                    draggedHand = null
                    dragStartTime = null
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun detectHandTouch(x: Float, y: Float, distance: Float): ClockHand? {
        val touchAngle = calculateAngle(centerX, centerY, x, y)
        val time = displayedTime

        // Calculate current hand angles
        val hours = if (is24Hour) time.hour else time.hour % 12
        val hourAngle = (hours + time.minute / 60f) * 30f - 90f
        val minuteAngle = (time.minute + time.second / 60f) * 6f - 90f
        val secondAngle = time.second * 6f - 90f

        val tolerance = 15f

        // Check distance is reasonable for hand interaction
        if (distance < radius * 0.2f || distance > radius * 0.9f) {
            return null
        }

        // Check second hand first (if visible) as it's on top
        if (displaySeconds && isAngleNear(touchAngle, secondAngle, tolerance)) {
            return ClockHand.SECOND
        }

        // Check minute hand
        if (isAngleNear(touchAngle, minuteAngle, tolerance)) {
            return ClockHand.MINUTE
        }

        // Check hour hand
        if (isAngleNear(touchAngle, hourAngle, tolerance)) {
            return ClockHand.HOUR
        }

        return null
    }

    private fun calculateNewTime(hand: ClockHand, angleDelta: Float): LocalTime {
        var newTime = displayedTime

        when (hand) {
            ClockHand.HOUR -> {
                // Hour hand moves 0.5 degrees per minute
                val minutesDelta = (angleDelta / 0.5f).toInt()
                newTime = newTime.plusMinutes(minutesDelta.toLong())
            }
            ClockHand.MINUTE -> {
                // Minute hand moves 6 degrees per minute
                val minutesDelta = (angleDelta / 6f).toInt()
                newTime = newTime.plusMinutes(minutesDelta.toLong())
            }
            ClockHand.SECOND -> {
                // Second hand moves 6 degrees per second
                val secondsDelta = (angleDelta / 6f).toInt()
                newTime = newTime.plusSeconds(secondsDelta.toLong())
            }
        }

        return newTime
    }

    // --- Helper Functions ---
    private fun calculateAngle(centerX: Float, centerY: Float, x: Float, y: Float): Float {
        val angleRad = atan2(y - centerY, x - centerX)
        var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f // Adjust so 0° is at 12 o'clock
        if (angleDeg < 0) angleDeg += 360f
        return angleDeg
    }

    private fun angleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle1 - angle2
        while (diff <= -180) diff += 360
        while (diff > 180) diff -= 360
        return diff
    }

    private fun isAngleNear(angle1: Float, angle2: Float, tolerance: Float): Boolean {
        val diff = abs(angleDifference(angle1, angle2))
        return diff < tolerance
    }
}