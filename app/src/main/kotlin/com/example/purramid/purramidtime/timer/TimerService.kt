// TimerService.kt
package com.example.purramid.purramidtime.timer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.MainActivity
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.timer.repository.TimerRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

// Service Actions
const val ACTION_START_TIMER = "com.example.purramid.purramidtime.timer.ACTION_START_TIMER"
const val ACTION_STOP_TIMER_SERVICE = "com.example.purramid.purramidtime.timer.ACTION_STOP_TIMER_SERVICE"
const val EXTRA_TIMER_ID = "timerId"
const val EXTRA_DURATION_MS = "com.example.purramid.purramidtime.timer.EXTRA_DURATION_MS"

@AndroidEntryPoint
class TimerService : LifecycleService() {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var timerCoordinator: TimerCoordinator
    @Inject lateinit var timerRepository: TimerRepository

    /**
     * Per-window state. The service is a process singleton, so everything that is
     * specific to a single timer window lives here, keyed by instanceId (1-4).
     */
    private class TimerInstance(val timerId: Int) {
        var overlayView: View? = null
        var layoutParams: WindowManager.LayoutParams? = null
        var isViewAdded = false
        var stateObserverJob: Job? = null

        // Cached views
        var digitalTimeTextView: TextView? = null
        var playPauseButton: ImageView? = null
        var settingsButton: ImageView? = null
        var closeButton: ImageView? = null
        var presetButton: ImageView? = null
        var resetButton: ImageView? = null

        // Finish-sound tracking
        var hasPlayedFinishSound = false
        var lastCountdownMillis = -1L
        var mediaPlayer: MediaPlayer? = null
    }

    private val instances = ConcurrentHashMap<Int, TimerInstance>()
    private var isForeground = false

    // Device-level constants (not per-instance)
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val movementThreshold: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val TAG = "TimerService"
        private const val NOTIFICATION_ID = 5
        private const val CHANNEL_ID = "TimerServiceChannel"
        private const val MAX_TIMERS = 4
        const val PREFS_NAME_FOR_ACTIVITY = "timer_prefs"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        restorePersistedTimers()
    }

    /**
     * Re-create windows for any timers that were active when the process was last
     * killed. Mirrors ClockOverlayService's restore path so a sticky restart does
     * not lose windows or leak instance slots.
     */
    private fun restorePersistedTimers() {
        lifecycleScope.launch {
            val ids = timerRepository.getAllPersistedTimerIds()
            if (ids.isEmpty()) return@launch
            Log.d(TAG, "Restoring ${ids.size} persisted timer(s): $ids")
            startForegroundServiceIfNeeded()
            ids.forEach { id ->
                instanceManager.registerExistingInstance(InstanceManager.TIMER, id)
                timerRepository.initializeTimer(id)
                addOrUpdateInstance(id)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val intentTimerId = intent?.getIntExtra(EXTRA_TIMER_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, ID: $intentTimerId")

        when (action) {
            ACTION_START_TIMER -> handleStartTimer(intent, intentTimerId)
            ACTION_STOP_TIMER_SERVICE -> {
                if (intentTimerId > 0) {
                    removeTimerInstance(intentTimerId)
                } else {
                    stopAllInstancesAndService()
                }
            }
            null -> Log.d(TAG, "Null intent (restart) - windows restored in onCreate")
            else -> Log.w(TAG, "Unhandled action: $action")
        }
        return START_STICKY
    }

    private fun handleStartTimer(intent: Intent, intentTimerId: Int) {
        val id = if (intentTimerId > 0) {
            instanceManager.registerExistingInstance(InstanceManager.TIMER, intentTimerId)
            intentTimerId
        } else {
            val newId = instanceManager.getNextInstanceId(InstanceManager.TIMER)
            if (newId == null) {
                Log.e(TAG, "No available instance slots for Timer")
                if (instances.isEmpty()) stopSelf()
                return
            }
            newId
        }

        startForegroundServiceIfNeeded()

        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        lifecycleScope.launch {
            timerRepository.initializeTimer(id)
            if (durationMs > 0) {
                timerRepository.setInitialDuration(id, durationMs)
            }
            addOrUpdateInstance(id)
        }
    }

    private suspend fun addOrUpdateInstance(id: Int) = withContext(Dispatchers.Main) {
        val instance = instances.getOrPut(id) { TimerInstance(id) }
        if (instance.overlayView == null) {
            createAndAddTimerWindow(instance)
        }
        if (instance.stateObserverJob == null) {
            observeTimerState(instance)
        }
        updateOverlayViews(instance, timerRepository.getCurrentState(id))
    }

    private fun observeTimerState(instance: TimerInstance) {
        instance.stateObserverJob?.cancel()
        instance.stateObserverJob = lifecycleScope.launch {
            timerRepository.getTimerStateFlow(instance.timerId).collectLatest { state ->
                if (instance.overlayView == null) {
                    createAndAddTimerWindow(instance)
                } else {
                    updateOverlayViews(instance, state)
                }
                updateLayoutParamsIfNeeded(instance, state)
                handleFinishSound(instance, state)
            }
        }
        Log.d(TAG, "Observing timer state for timer ${instance.timerId}")
    }

    private fun handleFinishSound(instance: TimerInstance, state: TimerState) {
        // Reset flag when the countdown restarts
        if (state.currentMillis > 0 && instance.lastCountdownMillis == 0L) {
            instance.hasPlayedFinishSound = false
        }
        // Play sound when the countdown reaches zero
        if (state.currentMillis == 0L &&
            !state.isRunning &&
            state.playSoundOnEnd &&
            !instance.hasPlayedFinishSound) {
            playFinishSound(instance, state)
            instance.hasPlayedFinishSound = true
        }
        instance.lastCountdownMillis = state.currentMillis
    }

    private fun createAndAddTimerWindow(instance: TimerInstance) {
        val state = timerRepository.getCurrentState(instance.timerId)
        val params = createDefaultLayoutParams()
        applyStateToLayoutParams(state, params)
        instance.layoutParams = params

        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_timer, null)
        instance.overlayView = view

        cacheViews(instance)
        setupListeners(instance)
        setupWindowDragListener(instance)

        try {
            windowManager.addView(view, params)
            instance.isViewAdded = true
            Log.d(TAG, "Timer overlay view added for ${instance.timerId}.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding timer overlay view for ${instance.timerId}", e)
            cleanupInstanceViews(instance)
        }
        updateOverlayViews(instance, state)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or  // touch pass-through
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,   // proper positioning
            PixelFormat.TRANSLUCENT  // enables transparency
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun cacheViews(instance: TimerInstance) {
        val view = instance.overlayView ?: return
        instance.digitalTimeTextView = view.findViewById(R.id.digitalTimeTextView)
        instance.playPauseButton = view.findViewById(R.id.playPauseButton)
        instance.settingsButton = view.findViewById(R.id.settingsButton)
        instance.closeButton = view.findViewById(R.id.closeButton)
        instance.resetButton = view.findViewById(R.id.resetButton)
        instance.presetButton = view.findViewById(R.id.presetButton)
    }

    private fun setupListeners(instance: TimerInstance) {
        val id = instance.timerId
        instance.playPauseButton?.setOnClickListener {
            lifecycleScope.launch { timerRepository.togglePlayPause(id) }
        }
        instance.closeButton?.setOnClickListener { removeTimerInstance(id) }
        instance.settingsButton?.setOnClickListener { openSettings(id) }
        instance.resetButton?.setOnClickListener {
            lifecycleScope.launch { timerRepository.resetTimer(id) }
        }
        instance.presetButton?.setOnClickListener { showPresetTimesPopup(id) }
    }

    private fun showPresetTimesPopup(id: Int) {
        val intent = Intent(this, TimerActivity::class.java).apply {
            action = TimerActivity.ACTION_SHOW_PRESETS
            putExtra(EXTRA_TIMER_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun updateOverlayViews(instance: TimerInstance, state: TimerState) {
        if (instance.overlayView == null || !instance.isViewAdded) return

        // Apply nested state if needed
        applyNestedState(instance, state)

        instance.overlayView?.setBackgroundColor(state.overlayColor)

        // Use proper luminance calculation for text color
        val textColor = if (ColorUtils.calculateLuminance(state.overlayColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        instance.digitalTimeTextView?.text = formatTime(state.currentMillis)
        instance.digitalTimeTextView?.setTextColor(textColor)

        instance.playPauseButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        instance.playPauseButton?.setImageResource(if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        instance.playPauseButton?.contentDescription = getString(if (state.isRunning) R.string.pause else R.string.play)

        instance.closeButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)

        instance.resetButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        instance.resetButton?.isEnabled = !state.isRunning && (state.currentMillis != state.initialDurationMillis || state.currentMillis == 0L)

        instance.presetButton?.visibility = if (state.showPresetButton && !state.isNested) {
            View.VISIBLE
        } else {
            View.GONE
        }
        instance.presetButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
    }

    private fun applyStateToLayoutParams(state: TimerState, params: WindowManager.LayoutParams) {
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

    private fun updateLayoutParamsIfNeeded(instance: TimerInstance, state: TimerState) {
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

    private fun removeInstanceView(instance: TimerInstance) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { removeInstanceView(instance) }
            return
        }
        instance.overlayView?.let {
            if (instance.isViewAdded && it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                    Log.d(TAG, "Timer overlay view removed for ${instance.timerId}.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing timer overlay view for ${instance.timerId}", e)
                }
            }
        }
        cleanupInstanceViews(instance)
    }

    private fun cleanupInstanceViews(instance: TimerInstance) {
        instance.overlayView = null
        instance.layoutParams = null
        instance.isViewAdded = false
        instance.digitalTimeTextView = null
        instance.playPauseButton = null
        instance.resetButton = null
        instance.settingsButton = null
        instance.closeButton = null
        instance.presetButton = null
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds)
            else -> String.format("%02d", seconds)
        }
    }

    private fun openSettings(id: Int) {
        val intent = Intent(this, TimerActivity::class.java).apply {
            action = TimerActivity.ACTION_SHOW_TIMER_SETTINGS
            putExtra(EXTRA_TIMER_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener(instance: TimerInstance) {
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
                        lifecycleScope.launch {
                            timerRepository.updateWindowPosition(instance.timerId, params.x, params.y)
                        }
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
     * Remove a single timer window. Stops the service only once the last window is
     * closed.
     */
    private fun removeTimerInstance(id: Int) {
        val instance = instances.remove(id) ?: return
        instance.stateObserverJob?.cancel()
        releaseMediaPlayer(instance)
        removeInstanceView(instance)
        instanceManager.releaseInstanceId(InstanceManager.TIMER, id)
        lifecycleScope.launch { timerRepository.deleteTimer(id) }

        if (instances.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    private fun stopAllInstancesAndService() {
        instances.keys.toList().forEach { removeTimerInstance(it) }
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
            releaseMediaPlayer(instance)
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
            Log.d(TAG, "TimerService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for Timer", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.timer_notification_title))
            .setContentText(getString(R.string.timer_notification_content))
            .setSmallIcon(R.drawable.ic_timer_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.timer_notification_channel_name),
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

    private fun applyNestedState(instance: TimerInstance, state: TimerState) {
        val params = instance.layoutParams
        if (state.isNested) {
            params?.apply {
                width = dpToPx(75)
                height = dpToPx(75)

                // If position not yet set, use default top-right
                if (state.nestedX == -1 || state.nestedY == -1) {
                    x = resources.displayMetrics.widthPixels - width - dpToPx(20)
                    y = dpToPx(20)

                    // Stack if other nested timers exist
                    lifecycleScope.launch {
                        val stackPosition = timerCoordinator.getNestedTimerStackPosition(instance.timerId)
                        withContext(Dispatchers.Main) {
                            instance.layoutParams?.let { p ->
                                p.y = dpToPx(20) + (stackPosition * (p.height + dpToPx(10)))
                                timerRepository.updateNestedPosition(instance.timerId, p.x, p.y)
                                if (instance.isViewAdded && instance.overlayView != null) {
                                    try {
                                        windowManager.updateViewLayout(instance.overlayView, p)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error updating nested position", e)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    x = state.nestedX
                    y = state.nestedY
                }

                gravity = Gravity.TOP or Gravity.START
            }

            // Hide control buttons for nested view
            instance.playPauseButton?.visibility = View.GONE
            instance.resetButton?.visibility = View.GONE
            instance.settingsButton?.visibility = View.GONE
            instance.presetButton?.visibility = View.GONE

            instance.digitalTimeTextView?.textSize = 20f
        } else {
            // Restore normal view
            instance.playPauseButton?.visibility = View.VISIBLE
            instance.settingsButton?.visibility = View.VISIBLE
            instance.resetButton?.visibility = View.VISIBLE

            instance.digitalTimeTextView?.textSize = 36f
        }

        if (instance.isViewAdded && instance.overlayView != null && params != null) {
            try {
                windowManager.updateViewLayout(instance.overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating nested state layout", e)
            }
        }
    }

    private fun playFinishSound(instance: TimerInstance, state: TimerState) {
        try {
            when {
                // First priority: Custom music URL
                !state.musicUrl.isNullOrEmpty() -> playMusicUrl(instance, state.musicUrl)
                // Second priority: Selected system sound
                !state.selectedSoundUri.isNullOrEmpty() -> playSystemSound(state.selectedSoundUri)
                // Default: Play default notification sound
                else -> playDefaultSound()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing finish sound", e)
        }
    }

    private fun playMusicUrl(instance: TimerInstance, url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                releaseMediaPlayer(instance)
                val player = MediaPlayer().apply {
                    setDataSource(url)
                    setOnPreparedListener {
                        it.start()
                        Log.d(TAG, "Playing music URL: $url")
                    }
                    setOnCompletionListener { releaseMediaPlayer(instance) }
                    setOnErrorListener { _, _, _ ->
                        Log.e(TAG, "Error playing music URL: $url")
                        releaseMediaPlayer(instance)
                        lifecycleScope.launch(Dispatchers.Main) { playDefaultSound() }
                        true
                    }
                    prepareAsync()
                }
                instance.mediaPlayer = player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play music URL", e)
                releaseMediaPlayer(instance)
                withContext(Dispatchers.Main) { playDefaultSound() }
            }
        }
    }

    private fun releaseMediaPlayer(instance: TimerInstance) {
        try {
            instance.mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing media player", e)
        }
        instance.mediaPlayer = null
    }

    private fun playSystemSound(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
            Log.d(TAG, "Playing system sound: $uriString")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play system sound", e)
            playDefaultSound() // Fallback
        }
    }

    private fun playDefaultSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, notification)
            ringtone?.play()
            Log.d(TAG, "Playing default notification sound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play default sound", e)
        }
    }
}
