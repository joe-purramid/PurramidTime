// ClockView.kt
package com.example.purramid.purramidtime.clock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.util.dpToPx
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
import kotlin.math.sin
import androidx.core.graphics.withRotation

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

    // Add throttling variables
    private var lastUpdateTime = 0L
    private val UPDATE_THROTTLE_MS = 16L // ~60 FPS max

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

    // --- Touch Handling State ---
    private enum class ClockHand { HOUR, MINUTE, SECOND }
    private var draggedHand: ClockHand? = null
    private var lastTouchAngle: Float = 0f
    private var isDraggingHand = false

    // --- Drawing Tools ---
    // Typography follows docs/design/README.md ("Design Tokens"). Sizes there are
    // quoted against the reference window (340x230dp digital / 220dp analog face);
    // this view is resizable, so each size is scaled from that reference rather
    // than pinned, and hits the design value exactly at the reference size.
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, WEIGHT_DIGITAL_TIME, false)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics)
    }

    private val amPmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, WEIGHT_AM_PM, false)
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, WEIGHT_NUMERAL_PRIMARY, false)
    }

    private val secondaryNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, WEIGHT_NUMERAL_SECONDARY, false)
    }

    // Design tokens, resolved once (values/colors.xml).
    private val colorTextPrimary = ContextCompat.getColor(context, R.color.clock_text_primary)
    private val colorAccentActive = ContextCompat.getColor(context, R.color.icon_state_active)

    private lateinit var timeFormatter: DateTimeFormatter
    private val bounds = Rect()

    // Localized period labels, refreshed with the formatter so a locale change
    // carries through (the design draws both, so both are needed up front).
    private var amLabel: String = ""
    private var pmLabel: String = ""

    // --- Initialization ---
    init {
        // Initialize formatter
        updateTimeFormat()
        setPaintColors()
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

    /** Public method to check if hand dragging is in progress */
    fun isHandDragging(): Boolean = isDraggingHand

    // --- Internal Helper Methods ---

    private fun setPaintColors() {
        val contrastColor = getContrastColor(clockColor)
        textPaint.color = contrastColor
        handPaint.color = contrastColor
        numberPaint.color = contrastColor
        secondaryNumberPaint.color = secondaryNumeralColor()
    }

    /** The face colour, forced opaque — contrast maths needs an opaque backdrop. */
    private fun opaqueFace(): Int = ColorUtils.setAlphaComponent(clockColor, 255)

    /**
     * Picks whichever ink contrasts better against the face (spec 6.1.1). The
     * design's #1c1c1c stands in for black, which is what it is on the white face.
     *
     * This replaced a hardcoded per-palette map that assigned white ink to VIOLET
     * (#EE82EE) — a light colour, where white lands at 2.32:1 and fails AA. Reading
     * contrast off the colour keeps custom faces honest too.
     */
    private fun getContrastColor(backgroundColor: Int): Int {
        val face = ColorUtils.setAlphaComponent(backgroundColor, 255)
        val darkContrast = ColorUtils.calculateContrast(colorTextPrimary, face)
        val lightContrast = ColorUtils.calculateContrast(Color.WHITE, face)
        return if (darkContrast >= lightContrast) colorTextPrimary else Color.WHITE
    }

    /**
     * The 13-24 ring reads as a dimmer tier of the ink. The design states that
     * relationship once — #6b6b6b against #1c1c1c ink on a white face, i.e. the ink
     * dimmed ~35% toward the face — so applying it as a ratio carries the tier onto
     * every face rather than only the white one.
     *
     * Dimming costs contrast, and the mid-luminance palette entries have none to
     * spare (#6b6b6b is 1.12:1 on teal). Where the dimmed value cannot hold AA, fall
     * back to full ink and let the smaller size and lighter weight carry the
     * hierarchy on their own.
     */
    private fun secondaryNumeralColor(): Int {
        val face = opaqueFace()
        val ink = getContrastColor(face)
        val dimmed = ColorUtils.blendARGB(ink, face, SECONDARY_NUMERAL_FACE_BLEND)
        return if (ColorUtils.calculateContrast(dimmed, face) >= MIN_TEXT_CONTRAST) dimmed else ink
    }

    /**
     * The design lights the active period in #1976D2. That accent only clears AA on
     * the white and black faces (~2:1 on goldenrod, teal and violet), so fall back
     * to the face's ink where it cannot hold. Since only the active period is drawn
     * (spec 6.1.2.1.1.2.2), the colour is decoration rather than the state signal —
     * the label itself says which period it is — so nothing is lost by dropping it.
     */
    private fun amPmColor(): Int {
        val face = opaqueFace()
        return if (ColorUtils.calculateContrast(colorAccentActive, face) >= MIN_TEXT_CONTRAST) {
            colorAccentActive
        } else {
            getContrastColor(face)
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

        val amPmFormatter = DateTimeFormatter.ofPattern("a", locale)
        amLabel = SAMPLE_AM_TIME.format(amPmFormatter)
        pmLabel = SAMPLE_PM_TIME.format(amPmFormatter)
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
        numberPaint.textSize = radius * NUMERAL_PRIMARY_RADIUS_RATIO

        // Draw 12-hour numbers
        val primaryBounds = Rect()
        numberPaint.getTextBounds("12", 0, 2, primaryBounds)
        val primaryHeight = primaryBounds.height()

        for (number in 1..12) {
            val angle = Math.toRadians((number * 30 - 90).toDouble())
            val numberRadius = radius * NUMERAL_PRIMARY_ORBIT_RATIO

            val x = centerX + (numberRadius * cos(angle)).toFloat()
            val y = centerY + (numberRadius * sin(angle)).toFloat() + primaryHeight / 2f

            canvas.drawText(number.toString(), x, y, numberPaint)
        }

        // Draw 24-hour numbers if enabled
        if (is24Hour) {
            secondaryNumberPaint.textSize = radius * NUMERAL_SECONDARY_RADIUS_RATIO

            val secondaryBounds = Rect()
            secondaryNumberPaint.getTextBounds("24", 0, 2, secondaryBounds)
            val secondaryHeight = secondaryBounds.height()

            for (number in 13..24) {
                val displayNumber = if (number == 24) "00" else number.toString()
                val hourNumber = if (number == 24) 12 else number - 12
                val angle = Math.toRadians((hourNumber * 30 - 90).toDouble())
                val numberRadius = radius * NUMERAL_SECONDARY_ORBIT_RATIO

                val x = centerX + (numberRadius * cos(angle)).toFloat()
                val y = centerY + (numberRadius * sin(angle)).toFloat() + secondaryHeight / 2f

                canvas.drawText(displayNumber, x, y, secondaryNumberPaint)
            }
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
        canvas.withRotation(angle, centerX, centerY) {
            paint.strokeWidth = width

            // Draw the main hand
            drawLine(
                centerX,
                centerY,
                centerX,
                centerY - length,
                paint
            )

            // Draw a tail for balance
            val tailLength = length * 0.2f
            drawLine(
                centerX,
                centerY,
                centerX,
                centerY + tailLength,
                paint
            )

        }
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
            drawAmPmIndicator(canvas, x, textPaint.measureText(formattedTime))
        }
    }

    /**
     * Draws the active period to the right of the readout. AM and PM occupy a
     * two-line stack with AM on top and PM below, but only the active one is drawn
     * (spec 6.1.2.1.1.2.1 through 6.1.2.1.1.2.3) — so each label keeps its own slot
     * rather than being centred on the readout.
     *
     * Both the horizontal offset and the reserved width are measured from the wider
     * of the two labels, so the readout does not shift at noon or midnight, in
     * locales where the period labels differ in width.
     */
    private fun drawAmPmIndicator(canvas: Canvas, timeCenterX: Float, timeWidth: Float) {
        amPmPaint.textSize = textPaint.textSize * AM_PM_TO_TIME_RATIO

        val isAm = displayedTime.hour < 12
        val label = if (isAm) amLabel else pmLabel
        val labelWidth = max(amPmPaint.measureText(amLabel), amPmPaint.measureText(pmLabel))
        val x = timeCenterX + (timeWidth / 2f) + (labelWidth / 2f) + context.dpToPx(4)

        // The stack straddles the readout's vertical centre: AM's slot above it,
        // PM's below.
        val metrics = amPmPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val amBaseline = (height / 2f) - lineHeight - metrics.ascent
        val baseline = if (isAm) amBaseline else amBaseline + lineHeight

        amPmPaint.color = amPmColor()
        canvas.drawText(label, x, baseline, amPmPaint)
    }

    /**
     * The design pins the readout at 60dp in the reference 340dp-wide window. The
     * window is resizable, so scale from that reference — hitting 60dp exactly at
     * reference width — then shrink to fit if the AM/PM stack would not clear.
     */
    private fun getDigitalTextSize(availableWidth: Int): Float {
        val referenceWidth = context.dpToPx(REFERENCE_WINDOW_WIDTH_DP).toFloat()
        val minSize = context.dpToPx(MIN_DIGITAL_TEXT_SIZE_DP).toFloat()
        var textSize =
            context.dpToPx(DESIGN_DIGITAL_TEXT_SIZE_DP) * (availableWidth / referenceWidth)

        val sampleText = if (displaySeconds) "00:00:00" else "00:00"
        val maxWidth = availableWidth * 0.9f

        // The AM/PM stack sits beside the readout in 12-hour mode and has to fit too.
        fun composedWidth(size: Float): Float {
            textPaint.textSize = size
            if (is24Hour) return textPaint.measureText(sampleText)
            amPmPaint.textSize = size * AM_PM_TO_TIME_RATIO
            return textPaint.measureText(sampleText) +
                    max(amPmPaint.measureText(amLabel), amPmPaint.measureText(pmLabel)) +
                    context.dpToPx(4)
        }

        while (composedWidth(textSize) > maxWidth && textSize > minSize) {
            textSize *= 0.95f
        }

        textPaint.textSize = textSize
        return max(textSize, minSize)
    }

    // --- Touch Handling for Analog Clock ---
    @SuppressLint("ClickableViewAccessibility")
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
                    isDraggingHand = true
                    interactionListener?.onDragStateChanged(instanceId, true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                draggedHand?.let { hand ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime < UPDATE_THROTTLE_MS) {
                        return true
                    }
                    lastUpdateTime = currentTime

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
                    isDraggingHand = false
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

    companion object {
        // Font weights (docs/design/README.md, "Typography").
        private const val WEIGHT_DIGITAL_TIME = 600
        private const val WEIGHT_AM_PM = 700
        private const val WEIGHT_NUMERAL_PRIMARY = 600
        private const val WEIGHT_NUMERAL_SECONDARY = 500

        // Digital readout: 60dp in the 340dp-wide reference window; AM/PM is 19dp
        // against that 60dp, so the pair keeps its proportion as the window resizes.
        private const val REFERENCE_WINDOW_WIDTH_DP = 340
        private const val DESIGN_DIGITAL_TEXT_SIZE_DP = 60
        private const val MIN_DIGITAL_TEXT_SIZE_DP = 12
        private const val AM_PM_TO_TIME_RATIO = 19f / 60f

        // Analog numerals, as ratios of the drawn face radius. The design quotes
        // 20dp (1-12) and 12dp (13-24) on a 220dp face, i.e. a 110dp radius.
        private const val NUMERAL_PRIMARY_RADIUS_RATIO = 20f / 110f
        private const val NUMERAL_SECONDARY_RADIUS_RATIO = 12f / 110f

        // Hour ticks run from 0.95r inward to 0.85r; the 1-12 ring is seated far
        // enough in that the glyphs clear them.
        private const val NUMERAL_PRIMARY_ORBIT_RATIO = 0.72f
        private const val NUMERAL_SECONDARY_ORBIT_RATIO = 0.55f

        // Sample times used only to read the locale's AM and PM labels back out.
        private val SAMPLE_AM_TIME: LocalTime = LocalTime.of(9, 0)
        private val SAMPLE_PM_TIME: LocalTime = LocalTime.of(21, 0)

        // WCAG 2.2 AA for normal-size text. The clock's own numerals are large, but
        // the 13-24 ring is small enough to want the stricter bar.
        private const val MIN_TEXT_CONTRAST = 4.5

        // The design's #6b6b6b inner numerals against #1c1c1c ink on a white face:
        // (0x6b - 0x1c) / (0xff - 0x1c) = 0.348 of the way from ink toward the face.
        private const val SECONDARY_NUMERAL_FACE_BLEND = 0.348f
    }
}