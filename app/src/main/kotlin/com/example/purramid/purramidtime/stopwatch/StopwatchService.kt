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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.MainActivity
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.stopwatch.ui.LapTimesAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class StopwatchService : LifecycleService() {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var stopwatchRepository: StopwatchRepository

    /**
     * Per-window state. The service is a process singleton, so everything specific
     * to a single stopwatch window lives here, keyed by instanceId (1-4).
     *
     * The [state] flow is updated in-memory by the ticker (every tick) for smooth
     * display; it is only persisted on discrete events (play/pause, reset, lap,
     * move, settings) to avoid a per-tick database write storm.
     */
    private class StopwatchInstance(val stopwatchId: Int) {
        val state = MutableStateFlow(StopwatchState(stopwatchId = stopwatchId))
        var overlayView: View? = null
        var layoutParams: WindowManager.LayoutParams? = null
        var isViewAdded = false
        var stateObserverJob: Job? = null
        var tickerJob: Job? = null

        // Cached views
        var digitalTimeTextView: TextView? = null
        var playPauseButton: ImageView? = null
        var settingsButton: ImageView? = null
        var closeButton: ImageView? = null
        var lapResetButton: ImageView? = null
        var lapTimesLayout: LinearLayout? = null
        var noLapsTextView: TextView? = null
        var lapTimesRecyclerView: RecyclerView? = null
        val lapTimesAdapter = LapTimesAdapter()
    }

    private val instances = ConcurrentHashMap<Int, StopwatchInstance>()
    private var isForeground = false

    // Device-level constants (not per-instance)
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
        restorePersistedStopwatches()
    }

    /**
     * Re-create windows for any stopwatches that were active when the process was
     * last killed. Mirrors ClockOverlayService's restore path so a sticky restart
     * does not lose windows or leak instance slots.
     */
    private fun restorePersistedStopwatches() {
        lifecycleScope.launch {
            val ids = stopwatchRepository.getAllInstanceIds()
            if (ids.isEmpty()) return@launch
            Log.d(TAG, "Restoring ${ids.size} persisted stopwatch(es): $ids")
            startForegroundServiceIfNeeded()
            ids.forEach { id ->
                instanceManager.registerExistingInstance(InstanceManager.STOPWATCH, id)
                initializeInstance(id)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val intentStopwatchId = intent?.getIntExtra(EXTRA_STOPWATCH_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, ID: $intentStopwatchId")

        when (action) {
            ACTION_START_STOPWATCH -> handleStartStopwatch(intentStopwatchId)
            ACTION_STOP_STOPWATCH_SERVICE -> {
                if (intentStopwatchId > 0) {
                    removeStopwatchInstance(intentStopwatchId)
                } else {
                    stopAllInstancesAndService()
                }
            }
            ACTION_UPDATE_SETTING -> handleSettingUpdate(intent)
            null -> Log.d(TAG, "Null intent (restart) - windows restored in onCreate")
            else -> Log.w(TAG, "Unhandled action: $action")
        }
        return START_STICKY
    }

    private fun handleStartStopwatch(intentStopwatchId: Int) {
        val id = if (intentStopwatchId > 0) {
            instanceManager.registerExistingInstance(InstanceManager.STOPWATCH, intentStopwatchId)
            intentStopwatchId
        } else {
            val newId = instanceManager.getNextInstanceId(InstanceManager.STOPWATCH)
            if (newId == null) {
                Log.e(TAG, "No available instance slots for Stopwatch")
                if (instances.isEmpty()) stopSelf()
                return
            }
            newId
        }

        startForegroundServiceIfNeeded()
        lifecycleScope.launch { initializeInstance(id) }
    }

    private suspend fun initializeInstance(id: Int) {
        // Already active: just make sure the window and observer exist.
        if (instances.containsKey(id)) {
            withContext(Dispatchers.Main) {
                val instance = instances[id] ?: return@withContext
                if (instance.overlayView == null) createAndAddStopwatchWindow(instance)
                if (instance.stateObserverJob == null) observeStateChanges(instance)
                updateOverlayViews(instance, instance.state.value)
            }
            return
        }

        val loaded = stopwatchRepository.getStopwatchState(id)
        withContext(Dispatchers.Main) {
            val instance = instances.getOrPut(id) { StopwatchInstance(id) }
            instance.state.value = loaded
            createAndAddStopwatchWindow(instance)
            observeStateChanges(instance)
            if (loaded.isRunning) startTicker(instance)
            updateOverlayViews(instance, loaded)
        }
    }

    private fun observeStateChanges(instance: StopwatchInstance) {
        instance.stateObserverJob?.cancel()
        instance.stateObserverJob = lifecycleScope.launch {
            instance.state.collect { state ->
                updateOverlayViews(instance, state)
                updateLayoutParamsIfNeeded(instance, state)
            }
        }
    }

    private fun handleSettingUpdate(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_STOPWATCH_ID, 0)
        val key = intent.getStringExtra(EXTRA_SETTING_KEY) ?: return
        val instance = instances[id] ?: return

        val updatedState = when (key) {
            "showCentiseconds" ->
                instance.state.value.copy(showCentiseconds = intent.getBooleanExtra(EXTRA_SETTING_VALUE, true))
            "overlayColor" ->
                instance.state.value.copy(overlayColor = intent.getIntExtra(EXTRA_SETTING_VALUE, Color.WHITE))
            "soundsEnabled" ->
                instance.state.value.copy(soundsEnabled = intent.getBooleanExtra(EXTRA_SETTING_VALUE, false))
            "showLapTimes" ->
                instance.state.value.copy(showLapTimes = intent.getBooleanExtra(EXTRA_SETTING_VALUE, false))
            else -> instance.state.value
        }

        instance.state.value = updatedState
        lifecycleScope.launch { stopwatchRepository.saveStopwatchState(updatedState) }
    }

    // --- Stopwatch controls ---

    private fun togglePlayPause(instance: StopwatchInstance) {
        val newRunningState = !instance.state.value.isRunning
        val updatedState = instance.state.value.copy(isRunning = newRunningState)
        instance.state.value = updatedState

        if (newRunningState) startTicker(instance) else stopTicker(instance)

        lifecycleScope.launch { stopwatchRepository.saveStopwatchState(updatedState) }
    }

    private fun resetStopwatch(instance: StopwatchInstance) {
        stopTicker(instance)
        val updatedState = instance.state.value.copy(
            isRunning = false,
            currentMillis = 0L,
            laps = emptyList()
        )
        instance.state.value = updatedState
        lifecycleScope.launch { stopwatchRepository.saveStopwatchState(updatedState) }
    }

    private fun addLap(instance: StopwatchInstance) {
        val current = instance.state.value
        if (!current.isRunning || current.laps.size >= MAX_LAPS) return

        val updatedState = current.copy(laps = current.laps + current.currentMillis)
        instance.state.value = updatedState
        lifecycleScope.launch { stopwatchRepository.saveStopwatchState(updatedState) }
    }

    private fun startTicker(instance: StopwatchInstance) {
        if (instance.tickerJob?.isActive == true) return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = instance.state.value.currentMillis

        instance.tickerJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && instance.state.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = initialMillis + elapsed
                withContext(Dispatchers.Main.immediate) {
                    instance.state.value = instance.state.value.copy(currentMillis = newMillis)
                }
                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for stopwatch ${instance.stopwatchId}")
        }
    }

    private fun stopTicker(instance: StopwatchInstance) {
        instance.tickerJob?.cancel()
        instance.tickerJob = null
    }

    private fun updateWindowPosition(instance: StopwatchInstance, x: Int, y: Int) {
        val updatedState = instance.state.value.copy(windowX = x, windowY = y)
        instance.state.value = updatedState
        lifecycleScope.launch { stopwatchRepository.saveStopwatchState(updatedState) }
    }

    // --- Window management ---

    private fun createAndAddStopwatchWindow(instance: StopwatchInstance) {
        val params = createDefaultLayoutParams()
        applyStateToLayoutParams(instance.state.value, params)
        instance.layoutParams = params

        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_stopwatch, null)
        instance.overlayView = view

        cacheViews(instance)
        setupListeners(instance)
        setupWindowDragListener(instance)

        try {
            windowManager.addView(view, params)
            instance.isViewAdded = true
            Log.d(TAG, "Stopwatch overlay view added for ${instance.stopwatchId}.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding stopwatch overlay view for ${instance.stopwatchId}", e)
            cleanupInstanceViews(instance)
        }
        updateOverlayViews(instance, instance.state.value)
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

    private fun cacheViews(instance: StopwatchInstance) {
        val view = instance.overlayView ?: return
        instance.digitalTimeTextView = view.findViewById(R.id.digitalTimeTextView)
        instance.playPauseButton = view.findViewById(R.id.playPauseButton)
        instance.settingsButton = view.findViewById(R.id.settingsButton)
        instance.closeButton = view.findViewById(R.id.closeButton)
        instance.lapResetButton = view.findViewById(R.id.lapResetButton)
        instance.lapTimesLayout = view.findViewById(R.id.lapTimesLayout)
        instance.noLapsTextView = view.findViewById(R.id.noLapsTextView)
        instance.lapTimesRecyclerView = view.findViewById<RecyclerView>(R.id.lapTimesRecyclerView)?.apply {
            layoutManager = LinearLayoutManager(this@StopwatchService)
            adapter = instance.lapTimesAdapter
        }
    }

    private fun setupListeners(instance: StopwatchInstance) {
        instance.playPauseButton?.setOnClickListener {
            playButtonSound(instance)
            togglePlayPause(instance)
        }
        instance.closeButton?.setOnClickListener { removeStopwatchInstance(instance.stopwatchId) }
        instance.settingsButton?.setOnClickListener { openSettings(instance.stopwatchId) }
        instance.lapResetButton?.setOnClickListener {
            val state = instance.state.value
            if (state.isRunning && state.showLapTimes) {
                playButtonSound(instance)
                addLap(instance)
            } else if (state.currentMillis > 0L) {
                playButtonSound(instance)
                resetStopwatch(instance)
            }
        }
    }

    private fun updateOverlayViews(instance: StopwatchInstance, state: StopwatchState) {
        if (instance.overlayView == null || !instance.isViewAdded) return

        instance.overlayView?.setBackgroundColor(state.overlayColor)

        val textColor = if (ColorUtils.calculateLuminance(state.overlayColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        instance.digitalTimeTextView?.text = formatTime(state.currentMillis, state.showCentiseconds)
        instance.digitalTimeTextView?.setTextColor(textColor)

        instance.playPauseButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        instance.playPauseButton?.setImageResource(if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        instance.playPauseButton?.contentDescription = getString(if (state.isRunning) R.string.pause else R.string.play)

        instance.settingsButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        instance.closeButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)

        // Lap/Reset button: lap while running with laps enabled, otherwise reset.
        instance.lapResetButton?.let { button ->
            button.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            when {
                state.isRunning && state.showLapTimes -> {
                    button.visibility = View.VISIBLE
                    button.setImageResource(R.drawable.ic_lap)
                    button.contentDescription = getString(R.string.lap)
                    button.isEnabled = state.laps.size < MAX_LAPS
                }
                state.currentMillis > 0L -> {
                    button.visibility = View.VISIBLE
                    button.setImageResource(R.drawable.ic_reset)
                    button.contentDescription = getString(R.string.reset)
                    button.isEnabled = true
                }
                else -> button.visibility = View.GONE
            }
        }

        // Lap times list. Laps are stored oldest-first, which is also the display
        // order (spec 10.1.3.1.1 / 10.1.3.1.2), so no reversal is needed.
        val showLapContainer = state.showLapTimes && (state.isRunning || state.laps.isNotEmpty())
        instance.lapTimesLayout?.visibility = if (showLapContainer) View.VISIBLE else View.GONE

        val lapItems = state.laps.mapIndexed { index, lapMillis ->
            val number = index + 1
            val time = formatTime(lapMillis, state.showCentiseconds)
            LapTimesAdapter.LapItem(
                label = getString(R.string.lap_row_label, number, time),
                description = getString(R.string.lap_row_description, number, time)
            )
        }
        instance.lapTimesAdapter.submit(lapItems, textColor)
        instance.lapTimesRecyclerView?.visibility = if (lapItems.isNotEmpty()) View.VISIBLE else View.GONE

        instance.noLapsTextView?.visibility =
            if (state.showLapTimes && state.isRunning && state.laps.isEmpty()) View.VISIBLE else View.GONE
        instance.noLapsTextView?.setTextColor(textColor)
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

    private fun updateLayoutParamsIfNeeded(instance: StopwatchInstance, state: StopwatchState) {
        val params = instance.layoutParams ?: return
        if (!instance.isViewAdded || instance.overlayView == null) return
        var needsUpdate = false
        if (params.x != state.windowX || params.y != state.windowY) {
            params.x = state.windowX
            params.y = state.windowY
            params.gravity = Gravity.TOP or Gravity.START
            needsUpdate = true
        }
        val newWidth = if (state.windowWidth > 0) state.windowWidth else WindowManager.LayoutParams.WRAP_CONTENT
        val newHeight = if (state.windowHeight > 0) state.windowHeight else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width != newWidth || params.height != newHeight) {
            params.width = newWidth
            params.height = newHeight
            needsUpdate = true
        }

        if (needsUpdate) {
            try {
                windowManager.updateViewLayout(instance.overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating layout params from state", e)
            }
        }
    }

    private fun removeInstanceView(instance: StopwatchInstance) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { removeInstanceView(instance) }
            return
        }
        instance.overlayView?.let {
            if (instance.isViewAdded && it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                    Log.d(TAG, "Stopwatch overlay view removed for ${instance.stopwatchId}.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing stopwatch overlay view for ${instance.stopwatchId}", e)
                }
            }
        }
        cleanupInstanceViews(instance)
    }

    private fun cleanupInstanceViews(instance: StopwatchInstance) {
        instance.overlayView = null
        instance.layoutParams = null
        instance.isViewAdded = false
        instance.digitalTimeTextView = null
        instance.playPauseButton = null
        instance.lapResetButton = null
        instance.settingsButton = null
        instance.closeButton = null
        instance.lapTimesLayout = null
        instance.noLapsTextView = null
        instance.lapTimesRecyclerView?.adapter = null
        instance.lapTimesRecyclerView = null
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

        return if (includeCentiseconds) String.format("%s.%02d", timeString, centi) else timeString
    }

    private fun openSettings(id: Int) {
        val intent = Intent(this, StopwatchActivity::class.java).apply {
            action = StopwatchActivity.ACTION_SHOW_STOPWATCH_SETTINGS
            putExtra(EXTRA_STOPWATCH_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener(instance: StopwatchInstance) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        instance.overlayView?.setOnTouchListener { _, event ->
            val params = instance.layoutParams ?: return@setOnTouchListener false
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
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isMoving && (abs(deltaX) > movementThreshold || abs(deltaY) > movementThreshold)) {
                        isMoving = true
                    }

                    if (isMoving) {
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        try {
                            windowManager.updateViewLayout(instance.overlayView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating layout on move", e)
                        }
                    }
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoving) {
                        updateWindowPosition(instance, params.x, params.y)
                        isMoving = false
                        return@setOnTouchListener true
                    }
                    return@setOnTouchListener false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isMoving = false
                    return@setOnTouchListener false
                }
            }
            false
        }
    }

    /**
     * Remove a single stopwatch window. Stops the service only once the last window
     * is closed.
     */
    private fun removeStopwatchInstance(id: Int) {
        val instance = instances.remove(id) ?: return
        instance.stateObserverJob?.cancel()
        stopTicker(instance)
        removeInstanceView(instance)
        instanceManager.releaseInstanceId(InstanceManager.STOPWATCH, id)
        lifecycleScope.launch {
            stopwatchRepository.deleteStopwatch(id)
            stopwatchRepository.clearCache(id)
        }

        if (instances.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun stopAllInstancesAndService() {
        instances.keys.toList().forEach { removeStopwatchInstance(it) }
        if (instances.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        instances.values.forEach { instance ->
            instance.stateObserverJob?.cancel()
            stopTicker(instance)
            removeInstanceView(instance)
        }
        instances.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

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

    private fun playButtonSound(instance: StopwatchInstance) {
        if (instance.state.value.soundsEnabled) {
            playBeepSound()
        }
    }

    private fun playBeepSound() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            Handler(Looper.getMainLooper()).postDelayed({ toneGen.release() }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing beep sound", e)
        }
    }
}
