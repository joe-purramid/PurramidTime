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
import androidx.lifecycle.*
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.MainActivity
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.timer.viewmodel.TimerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

// Service Actions
const val ACTION_STOP_TIMER_SERVICE = "com.example.purramid.purramidtime.timer.ACTION_STOP_TIMER_SERVICE"
const val EXTRA_TIMER_ID = TimerViewModel.KEY_TIMER_ID
const val EXTRA_DURATION_MS = "com.example.purramid.purramidtime.timer.EXTRA_DURATION_MS"

@AndroidEntryPoint
class TimerService : LifecycleService() {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var timerCoordinator: TimerCoordinator

    private lateinit var viewModel: TimerViewModel

    private var overlayView: View? = null
    private var timerId: Int = 0
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isViewAdded = false
    private var stateObserverJob: Job? = null
    private var currentInflatedLayoutId: Int = 0

    // --- Cached Views (Common and Specific) ---
    // Common
    private var digitalTimeTextView: TextView? = null
    private var playPauseButton: ImageView? = null
    private var settingsButton: ImageView? = null
    private var closeButton: TextView? = null
    private var presetButton: ImageView? = null
    private var centisecondsTextView: TextView? = null
    private var resetButtonCountdown: ImageView? = null

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

    // Track if finish sound has been played for current countdown
    private var hasPlayedFinishSound = false
    private var lastCountdownMillis = -1L

    companion object {
        private const val TAG = "TimerService"
        private const val NOTIFICATION_ID = 5
        private const val CHANNEL_ID = "TimerServiceChannel"
        const val PREFS_NAME_FOR_ACTIVITY = "timer_prefs"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val intentTimerId = intent?.getIntExtra(EXTRA_TIMER_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, ID: $intentTimerId")

        // Handle instance management
        if (intentTimerId <= 0 && action != ACTION_STOP_TIMER_SERVICE) {
            // Request new instance ID from InstanceManager
            val instanceId = instanceManager.getNextInstanceId(InstanceManager.TIMER)
            if (instanceId == null) {
                Log.e(TAG, "No available instance slots for Timer")
                stopSelf()
                return START_NOT_STICKY
            }
            this.timerId = instanceId
            Log.d(TAG, "Allocated new instanceId: $timerId")
        } else if (intentTimerId > 0) {
            // Register existing instance with InstanceManager
            if (!instanceManager.registerExistingInstance(InstanceManager.TIMER, intentTimerId)) {
                Log.w(TAG, "Failed to register existing instance $intentTimerId")
            }
            this.timerId = intentTimerId
        }

        // Initialize ViewModel if needed (first start or ID change)
        if (!::viewModel.isInitialized || this.timerId != intentTimerId) {
            viewModel = ViewModelProvider(this)[TimerViewModel::class.java]
            viewModel.setTimerId(timerId)
            Log.d(TAG, "ViewModel initialized for timerId: $timerId")
            observeViewModelState()
        }

        // Handle actions
        when (action) {
            ACTION_START_TIMER -> {
                val duration = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                viewModel.setInitialDuration(duration)
                startForegroundServiceIfNeeded()
                lifecycleScope.launch { addOverlayViewIfNeeded() }
            }

            ACTION_STOP_TIMER_SERVICE -> {
                stopService()
            }
        }
        return START_STICKY
    }

    private fun observeViewModelState() {
        stateObserverJob?.cancel()
        stateObserverJob = lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d(TAG, "Collecting State: Type=${state.type}, Running=${state.isRunning}, Millis=${state.currentMillis}")
                if (overlayView == null || currentInflatedLayoutId != requiredLayoutId) {
                    Log.d(TAG, "State requires layout change. Current: $currentInflatedLayoutId, Required: $requiredLayoutId")
                    addOverlayViewIfNeeded()
                } else {
                    updateOverlayViews(state)
                }
                updateLayoutParamsIfNeeded(state)

                // Handle countdown finish sound
                if (state.type == TimerType.COUNTDOWN) {
                    // Reset flag when countdown restarts
                    if (state.currentMillis > 0 && lastCountdownMillis == 0L) {
                        hasPlayedFinishSound = false
                    }

                    // Play sound when countdown reaches zero
                    if (state.currentMillis == 0L &&
                        !state.isRunning &&
                        state.playSoundOnEnd &&
                        !hasPlayedFinishSound) {
                        playFinishSound(state)
                        hasPlayedFinishSound = true
                    }

                    lastCountdownMillis = state.currentMillis
                }
            }
        }
        Log.d(TAG, "Started observing ViewModel state for timer $timerId")
    }

    private suspend fun addOverlayViewIfNeeded() {
        withContext(Dispatchers.Main) {
            val currentState = viewModel.uiState.value
            val requiredLayoutId = R.layout.view_floating_timer

            if (overlayView == null) {
                layoutParams = createDefaultLayoutParams()
                applyStateToLayoutParams(currentState, layoutParams!!)

                val inflater = LayoutInflater.from(this@TimersService)
                overlayView = inflater.inflate(requiredLayoutId, null)
                currentInflatedLayoutId = requiredLayoutId

                cacheViews()
                setupListeners()
                setupWindowDragListener()

                try {
                    if (!isViewAdded) {
                        windowManager.addView(overlayView, layoutParams)
                        isViewAdded = true
                        Log.d(TAG, "Timer overlay view added.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding timer overlay view", e)
                    cleanupViewReferences()
                }
            }
            updateOverlayViews(currentState)
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
        resetButtonCountdown = overlayView?.findViewById(R.id.resetButton)
        presetButton = overlayView?.findViewById(R.id.presetButton)
    }

    private fun setupListeners() {
        playPauseButton?.setOnClickListener {
            playButtonSound()
            viewModel.togglePlayPause()
        }
        closeButton?.setOnClickListener { stopService() }
        settingsButton?.setOnClickListener { openSettings() }
        resetButtonCountdown?.setOnClickListener {
            playButtonSound()
            viewModel.resetTimer()
        }
        presetButton?.setOnClickListener {
            playButtonSound()
            showPresetTimesPopup()
        }
    }

    private fun showPresetTimesPopup() {
        // Create a popup window or start an activity to show preset times
        val intent = Intent(this, TimerActivity::class.java).apply {
            action = "com.example.purramid.purramidtime.timer.ACTION_SHOW_PRESETS"
            putExtra(EXTRA_TIMER_ID, timerId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun updateOverlayViews(state: TimerState) {
        if (overlayView == null || !isViewAdded) return

        // Apply nested state if needed
        applyNestedState(state)

        overlayView?.setBackgroundColor(state.overlayColor)

        // Use proper luminance calculation for text color
        val textColor = if (ColorUtils.calculateLuminance(state.overlayColor) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        val timeStr = formatTime(state.currentMillis, showCentiseconds)

        centisecondsTextView?.let {
            it.text = if (state.showCentiseconds && timeStr.contains('.')) ".${timeStr.substringAfterLast('.')}" else ""
            it.visibility = if (state.showCentiseconds) View.VISIBLE else View.GONE
            it.setTextColor(textColor)
        }

        playPauseButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        playPauseButton?.setImageResource(if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton?.contentDescription = getString(if (state.isRunning) R.string.pause else R.string.play)

        closeButton?.setTextColor(textColor)

        resetButtonCountdown?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        resetButtonCountdown?.isEnabled = !state.isRunning && (state.currentMillis != state.initialDurationMillis || state.currentMillis == 0L)

        presetButton?.visibility = if (state.type == TimerType.COUNTDOWN && state.showPresetButton && !state.isNested) {
            View.VISIBLE
        } else {
            View.GONE
        }
        presetButton?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
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

    private fun updateLayoutParamsIfNeeded(state: TimerState) {
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
                    Log.d(TAG, "Timer overlay view removed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing timer overlay view", e)
                }
            }
        }
        cleanupViewReferences()
    }

    private fun cleanupViewReferences() {
        overlayView = null
        layoutParams = null
        isViewAdded = false
        currentInflatedLayoutId = 0
        digitalTimeTextView = null
        playPauseButton = null
        resetButtonCountdown = null
        settingsButton = null
        closeButton = null
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
        val intent = Intent(this, TimerActivity::class.java).apply {
            action = TimerActivity.ACTION_SHOW_TIMER_SETTINGS
            putExtra(EXTRA_TIMER_ID, timerId)
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
                            viewModel.updateWindowPosition(params.x, params.y)
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
        if (timerId > 0) {
            instanceManager.releaseInstanceId(InstanceManager.TIMER, timerId)
            viewModel.deleteState()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stateObserverJob?.cancel()
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

    private fun applyNestedState(state: TimerState) {
        if (state.isNested) {
            layoutParams?.apply {
                width = dpToPx(75)
                height = dpToPx(75)

                // If position not yet set, use default top-right
                if (state.nestedX == -1 || state.nestedY == -1) {
                    // Calculate top-right position with padding
                    x = resources.displayMetrics.widthPixels - width - dpToPx(20)
                    y = dpToPx(20)

                    // Stack if other nested timers exist
                    lifecycleScope.launch {
                        val stackPosition = timerCoordinator.getNestedTimerStackPosition(timerId)
                        withContext(Dispatchers.Main) {
                            layoutParams?.let { params ->
                                params.y = dpToPx(20) + (stackPosition * (params.height + dpToPx(10)))

                                // Save the calculated position
                                viewModel.updateNestedPosition(params.x, params.y)

                                // Update view if already added
                                if (isViewAdded && overlayView != null) {
                                    try {
                                        windowManager.updateViewLayout(overlayView, params)
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
            playPauseButton?.visibility = View.GONE
            resetButtonCountdown?.visibility = View.GONE
            settingsButton?.visibility = View.GONE
            presetButton?.visibility = View.GONE

            // Keep only time display and close button visible
            digitalTimeTextView?.textSize = 20f
        } else {
            // Restore normal view
            playPauseButton?.visibility = View.VISIBLE
            settingsButton?.visibility = View.VISIBLE

            digitalTimeTextView?.textSize = 36f
            centisecondsTextView?.textSize = 20f
        }

        // Update layout if needed
        if (isViewAdded && overlayView != null) {
            try {
                windowManager.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating nested state layout", e)
            }
        }
    }

    private fun playFinishSound(state: TimerState) {
        try {
            when {
                // First priority: Custom music URL
                !state.musicUrl.isNullOrEmpty() -> {
                    playMusicUrl(state.musicUrl)
                }
                // Second priority: Selected system sound
                !state.selectedSoundUri.isNullOrEmpty() -> {
                    playSystemSound(state.selectedSoundUri)
                }
                // Default: Play default notification sound
                else -> {
                    playDefaultSound()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing finish sound", e)
        }
    }

    private fun playMusicUrl(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepareAsync()
                    setOnPreparedListener {
                        start()
                        Log.d(TAG, "Playing music URL: $url")
                    }
                    setOnCompletionListener {
                        release()
                    }
                    setOnErrorListener { _, _, _ ->
                        Log.e(TAG, "Error playing music URL: $url")
                        withContext(Dispatchers.Main) {
                            playDefaultSound() // Fallback
                        }
                        release()
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play music URL", e)
                withContext(Dispatchers.Main) {
                    playDefaultSound() // Fallback
                }
            }
        }
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