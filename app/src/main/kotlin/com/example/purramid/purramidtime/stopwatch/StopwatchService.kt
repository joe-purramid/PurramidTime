// StopwatchService.kt
package com.example.purramid.purramidtime.stopwatch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.MainActivity
import com.example.purramid.purramidtime.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class StopwatchService : LifecycleService() {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var stopwatchRepository: StopwatchRepository

    private var overlayView: View? = null
    private var stopwatchId: Int = 0
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false

    // State management
    private val _currentState = MutableStateFlow(StopwatchState())
    private val currentState: StateFlow<StopwatchState> = _currentState.asStateFlow()
    private var stateObserverJob: Job? = null
    private var tickerJob: Job? = null

    // Cached Views
    private var digitalTimeTextView: TextView? = null
    private var playPauseButton: ImageView? = null
    private var settingsButton: ImageView? = null
    private var closeButton: TextView? = null
    private var lapResetButton: Button? = null
    private var lapTimesLayout: LinearLayout? = null
    private var lapTimeTextViews = mutableListOf<TextView>()
    private var noLapsTextView: TextView? = null

    // Touch handling
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isMoving = false
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val movementThreshold: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "StopwatchService"
        private const val NOTIFICATION_ID = 6
        private const val CHANNEL_ID = "StopwatchServiceChannel"
        private const val TICK_INTERVAL_MS = 50L
        private const val MAX_LAPS = 10
        const val PREFS_NAME_FOR_ACTIVITY = "stopwatch_prefs"

        const val ACTION_START_STOPWATCH = "com.example.purramid.purramidtime.stopwatch.ACTION_START_STOPWATCH"
        const val ACTION_STOP_STOPWATCH_SERVICE = "com.example.purramid.purramidtime.stopwatch.ACTION_STOP_STOPWATCH_SERVICE"
        const val ACTION_UPDATE_SETTING = "com.example.purramid.purramidtime.stopwatch.ACTION_UPDATE_SETTING"
        const val EXTRA_STOPWATCH_ID = "com.example.purramid.purramidtime.stopwatch.EXTRA_STOPWATCH_ID"
        const val EXTRA_SETTING_KEY = "com.example.purramid.purramidtime.stopwatch.EXTRA_SETTING_KEY"
        const val EXTRA_SETTING_VALUE = "com.example.purramid.purramidtime.stopwatch.EXTRA_SETTING_VALUE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()

        // Clean up orphaned instances on service start
        lifecycleScope.launch(Dispatchers.IO) {
            val activeIds = instanceManager.getActiveInstanceIds(InstanceManager.STOPWATCH)
            stopwatchRepository.cleanupOrphanedInstances(activeIds)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val intentStopwatchId = intent?.getIntExtra(EXTRA_STOPWATCH_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, ID: $intentStopwatchId")

        // Handle instance management
        if (intentStopwatchId <= 0 && action != ACTION_STOP_STOPWATCH_SERVICE) {
            // Request new instance ID from InstanceManager
            val instanceId = instanceManager.getNextInstanceId(InstanceManager.STOPWATCH)
            if (instanceId == null) {
                Log.e(TAG, "No available instance slots for Stopwatch")
                stopSelf()
                return START_NOT_STICKY
            }
            this.stopwatchId = instanceId
            Log.d(TAG, "Allocated new instanceId: $stopwatchId")
        } else if (intentStopwatchId > 0) {
            // Register existing instance with InstanceManager
            if (!instanceManager.registerExistingInstance(InstanceManager.STOPWATCH, intentStopwatchId)) {
                Log.w(TAG, "Failed to register existing instance $intentStopwatchId")
            }
            this.stopwatchId = intentStopwatchId
        }

        // Handle actions
        when (action) {
            ACTION_START_STOPWATCH -> {
                startForegroundServiceIfNeeded()
                lifecycleScope.launch {
                    loadState()
                    addOverlayViewIfNeeded()
                }
            }
            ACTION_STOP_STOPWATCH_SERVICE -> {
                stopService()
            }
            ACTION_UPDATE_SETTING -> {
                handleSettingUpdate(intent)
            }
        }
        return START_STICKY
    }

    private suspend fun loadState() {
        if (stopwatchId <= 0) return

        val state = stopwatchRepository.getStopwatchState(stopwatchId)
        _currentState.value = state

        if (state.isRunning) {
            startTicker()
        }

        observeStateChanges()
    }

    private fun observeStateChanges() {
        stateObserverJob?.cancel()
        stateObserverJob = lifecycleScope.launch {
            currentState.collect { state ->
                Log.d(TAG, "State changed: Running=${state.isRunning}, Millis=${state.currentMillis}")
                updateOverlayViews(state)
                updateLayoutParamsIfNeeded(state)
            }
        }
    }

    private fun handleSettingUpdate(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_SETTING_KEY) ?: return

        lifecycleScope.launch {
            val updatedState = when (key) {
                "showCentiseconds" -> {
                    val value = intent.getBooleanExtra(EXTRA_SETTING_VALUE, true)
                    _currentState.value.copy(showCentiseconds = value)
                }
                "overlayColor" -> {
                    val value = intent.getIntExtra(EXTRA_SETTING_VALUE, Color.WHITE)
                    _currentState.value.copy(overlayColor = value)
                }
                "soundsEnabled" -> {
                    val value = intent.getBooleanExtra(EXTRA_SETTING_VALUE, false)
                    _currentState.value.copy(soundsEnabled = value)
                }
                "showLapTimes" -> {
                    val value = intent.getBooleanExtra(EXTRA_SETTING_VALUE, false)
                    _currentState.value.copy(showLapTimes = value)
                }
                else -> _currentState.value
            }

            _currentState.value = updatedState
            stopwatchRepository.updateStopwatchState(updatedState)
        }
    }

    // Stopwatch control methods
    private fun togglePlayPause() {
        val newRunningState = !_currentState.value.isRunning
        val updatedState = _currentState.value.copy(isRunning = newRunningState)
        _currentState.value = updatedState

        if (newRunningState) {
            startTicker()
        } else {
            stopTicker()
        }

        lifecycleScope.launch {
            stopwatchRepository.saveStopwatchState(updatedState)
        }
    }

    private fun resetStopwatch() {
        stopTicker()
        val updatedState = _currentState.value.copy(
            isRunning = false,
            currentMillis = 0L,
            laps = emptyList()
        )
        _currentState.value = updatedState

        lifecycleScope.launch {
            stopwatchRepository.saveStopwatchState(updatedState)
        }
    }

    private fun addLap() {
        val currentState = _currentState.value
        if (!currentState.isRunning) return

        if (currentState.laps.size >= MAX_LAPS) {
            Log.d(TAG, "Maximum number of laps ($MAX_LAPS) reached")
            return
        }

        val currentLaps = currentState.laps.toMutableList()
        currentLaps.add(currentState.currentMillis)
        val updatedState = currentState.copy(laps = currentLaps)
        _currentState.value = updatedState

        lifecycleScope.launch {
            stopwatchRepository.saveStopwatchState(updatedState)
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = _currentState.value.currentMillis

        tickerJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && _currentState.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = initialMillis + elapsed

                withContext(Dispatchers.Main.immediate) {
                    _currentState.value = _currentState.value.copy(currentMillis = newMillis)
                }

                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for stopwatch $stopwatchId")
        }
        Log.d(TAG, "Ticker starting for stopwatch $stopwatchId...")
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        Log.d(TAG, "Ticker stopped for stopwatch $stopwatchId")
    }

    private fun updateWindowPosition(x: Int, y: Int) {
        val updatedState = _currentState.value.copy(windowX = x, windowY = y)
        _currentState.value = updatedState

        lifecycleScope.launch {
            stopwatchRepository.saveStopwatchState(updatedState)
        }
    }

    private suspend fun addOverlayViewIfNeeded() {
        withContext(Dispatchers.Main) {
            if (overlayView == null) {
                layoutParams = createDefaultLayoutParams()
                val currentState = _currentState.value
                applyStateToLayoutParams(currentState, layoutParams!!)

                val inflater = LayoutInflater.from(this@StopwatchService)
                overlayView = inflater.inflate(R.layout.view_floating_stopwatch, null)

                cacheViews()
                setupListeners()
                setupWindowDragListener()

                try {
                    if (!isViewAdded) {
                        windowManager.addView(overlayView, layoutParams)
                        isViewAdded = true
                        Log.d(TAG, "Stopwatch overlay view added.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding stopwatch overlay view", e)
                    cleanupViewReferences()
                }
            }
            updateOverlayViews(_currentState.value)
        }
    }

    private fun createDefaultLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun cacheViews() {
        if (overlayView == null) return
        digitalTimeTextView = overlayView?.findViewById(R.id.digitalTimeTextView)
        playPauseButton = overlayView?.findViewById(R.id.playPauseButton)
        settingsButton = overlayView?.findViewById(R.id.settingsButton)
        closeButton = overlayView?.findViewById(R.id.closeButton)
        lapResetButton = overlayView?.findViewById(R.id.lapResetButton)
        lapTimesLayout = overlayView?.findViewById(R.id.lapTimesLayout)
        noLapsTextView = overlayView?.findViewById(R.id.noLapsTextView)

        // Cache lap time text views
        lapTimeTextViews.clear()
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime1TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime2TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime3TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime4TextView) ?: TextView(this))
        lapTimeTextViews.add(overlayView?.findViewById(R.id.lapTime5TextView) ?: TextView(this))
        lapTimeTextViews.removeAll { it.id == View.NO_ID }
    }

    private fun setupListeners() {
        playPauseButton?.setOnClickListener {
            playButtonSound()
            togglePlayPause()
        }
        closeButton?.setOnClickListener { stopService() }
        settingsButton?.setOnClickListener { openSettings() }
        lapResetButton?.setOnClickListener {
            if (_currentState.value.isRunning) {
                playButtonSound()
                addLap()
            } else {
                playButtonSound()
                resetStopwatch()
            }
        }
    }

    private fun updateOverlayViews(state: StopwatchState) {
        if (overlayView == null || !isViewAdded) return

        overlayView?.setBackgroundColor(state.overlayColor)

        // Use proper luminance calculation for text color
        val textColor = if (ColorUtils.calculateLuminance(state.overlayColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        val showCenti = state.showCentiseconds
        val timeStr = formatTime(state.currentMillis, showCenti)

        digitalTimeTextView?.text = timeStr
        digitalTimeTextView?.setTextColor(textColor)

        playPauseButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        playPauseButton?.setImageResource(if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton?.contentDescription = getString(if (state.isRunning) R.string.pause else R.string.play)

        closeButton?.setTextColor(textColor)

        lapResetButton?.setTextColor(textColor)
        lapResetButton?.text = getString(if (state.isRunning) R.string.lap else R.string.reset)
        lapResetButton?.isEnabled = state.isRunning || state.currentMillis > 0L

        // Disable lap button if max laps reached
        if (state.isRunning && state.laps.size >= MAX_LAPS) {
            lapResetButton?.isEnabled = false
        }

        // Only show lap times if enabled in settings
        val hasLaps = state.laps.isNotEmpty() && state.showLapTimes
        lapTimesLayout?.visibility = if (hasLaps) View.VISIBLE else View.GONE
        noLapsTextView?.visibility = if (state.showLapTimes && !hasLaps) View.VISIBLE else View.GONE
        noLapsTextView?.setTextColor(textColor)

        if (hasLaps) {
            val reversedLaps = state.laps.reversed()
            lapTimeTextViews.forEachIndexed { index, textView ->
                if (index < reversedLaps.size) {
                    textView.text = "${state.laps.size - index}. ${formatTime(reversedLaps[index], true)}"
                    textView.visibility = View.VISIBLE
                    textView.setTextColor(textColor)
                } else {
                    textView.visibility = View.GONE
                }
            }
        }
    }

    private fun applyStateToLayoutParams(state: StopwatchState, params: WindowManager.LayoutParams) {
        params.x = state.windowX
        params.y = state.windowY
        params.width = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
        params.height = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
        if (state.windowX != 0 || state.windowY != 0) {
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            params.gravity = Gravity.CENTER
        }
    }

    private fun updateLayoutParamsIfNeeded(state: StopwatchState) {
        if (layoutParams == null || !isViewAdded || overlayView == null) return
        var needsUpdate = false
        if (layoutParams?.x != state.windowX || layoutParams?.y != state.windowY) {
            layoutParams?.x = state.windowX
            layoutParams?.y = state.windowY
            layoutParams?.gravity = Gravity.TOP or Gravity.START
            needsUpdate = true
        }
        val newWidth = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
        val newHeight = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
        if (layoutParams?.width != newWidth || layoutParams?.height != newHeight) {
            layoutParams?.width = newWidth
            layoutParams?.height = newHeight
            needsUpdate = true
        }

        if (needsUpdate) {
            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating layout params from state", e)
            }
        }
    }

    private fun removeOverlayView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "removeOverlayView called from non-UI thread. Posting to handler.")
            Handler(Looper.getMainLooper()).post { removeOverlayView() }
            return
        }
        overlayView?.let {
            if (isViewAdded && it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                    isViewAdded = false
                    Log.d(TAG, "Stopwatch overlay view removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing stopwatch overlay view", e)
                }
            }
        }
        cleanupViewReferences()
    }

    private fun cleanupViewReferences() {
        overlayView = null
        layoutParams = null
        isViewAdded = false
        digitalTimeTextView = null
        playPauseButton = null
        lapResetButton = null
        settingsButton = null
        closeButton = null
        lapTimesLayout = null
        lapTimeTextViews.clear()
        noLapsTextView = null
    }

    private fun formatTime(millis: Long, includeCentiseconds: Boolean): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = totalSeconds % 60
        val centi = (millis % 1000) / 10

        val timeString = when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds)
            else -> String.format("%02d", seconds)
        }

        return if (includeCentiseconds) {
            String.format("%s.%02d", timeString, centi)
        } else {
            timeString
        }
    }

    private fun openSettings() {
        val intent = Intent(this, StopwatchActivity::class.java).apply {
            action = StopwatchActivity.ACTION_SHOW_STOPWATCH_SETTINGS
            putExtra(EXTRA_STOPWATCH_ID, stopwatchId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener() {
        overlayView?.setOnTouchListener { _, event ->
            layoutParams?.let { params ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentX = event.rawX
                        val currentY = event.rawY
                        val deltaX = currentX - initialTouchX
                        val deltaY = currentY - initialTouchY

                        // Use movement threshold instead of touch slop
                        if (!isMoving && (abs(deltaX) > movementThreshold || abs(deltaY) > movementThreshold)) {
                            isMoving = true
                        }

                        if (isMoving) {
                            params.x = initialX + deltaX.toInt()
                            params.y = initialY + deltaY.toInt()
                            try {
                                windowManager.updateViewLayout(overlayView, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error updating layout on move", e)
                            }
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isMoving) {
                            updateWindowPosition(params.x, params.y)
                            isMoving = false
                        }
                        return@setOnTouchListener isMoving
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isMoving = false
                        return@setOnTouchListener false
                    }
                }
            }
            false
        }
    }

    private fun stopService() {
        if (stopwatchId > 0) {
            instanceManager.releaseInstanceId(InstanceManager.STOPWATCH, stopwatchId)

            // Check if this is the last instance
            val remainingInstances = instanceManager.getActiveInstanceCount(InstanceManager.STOPWATCH)

            lifecycleScope.launch {
                if (remainingInstances == 0) {
                    // Last instance - persist state for future use
                    Log.d(TAG, "Last stopwatch instance - preserving state")
                    stopwatchRepository.saveStopwatchState(_currentState.value)
                } else {
                    // Not the last instance - clean up from database
                    Log.d(TAG, "Cleaning up stopwatch instance $stopwatchId from database")
                    stopwatchRepository.deleteStopwatch(stopwatchId)
                }

                stopwatchRepository.clearCache(stopwatchId)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJob?.cancel()
        tickerJob?.cancel()
        removeOverlayView()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private var isForeground = false

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "StopwatchService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for Stopwatch", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.stopwatch_notification_title))
            .setContentText(getString(R.string.stopwatch_notification_content))
            .setSmallIcon(R.drawable.ic_stopwatch_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.stopwatch_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun playButtonSound() {
        if (_currentState.value.soundsEnabled) {
            playBeepSound()
        }
    }

    private fun playBeepSound() {
        try {
            val toneGen = ToneGenerator(
                AudioManager.STREAM_NOTIFICATION,
                100
            )
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            Handler(Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing beep sound", e)
        }
    }
}