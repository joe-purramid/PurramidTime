// ClockOverlayService.kt
package com.example.purramid.purramidtime.clock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.example.purramid.purramidtime.clock.repository.ClockRepository
import com.example.purramid.purramidtime.data.db.ClockStateEntity
import com.example.purramid.purramidtime.di.ClockPrefs
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.MainActivity
import com.example.purramid.purramidtime.R
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class ClockOverlayService : LifecycleService(), ClockView.ClockInteractionListener {

    @Inject lateinit var windowManager: WindowManager
    @Inject lateinit var instanceManager: InstanceManager
    @Inject lateinit var clockRepository: ClockRepository
    @Inject @ClockPrefs lateinit var servicePrefs: SharedPreferences

    private lateinit var clockStateManager: ClockStateManager
    private val activeClockViews = ConcurrentHashMap<Int, ViewGroup>()
    private val clockViewInstances = ConcurrentHashMap<Int, ClockView>()
    private val clockLayoutParams = ConcurrentHashMap<Int, WindowManager.LayoutParams>()
    private val stateObserverJobs = ConcurrentHashMap<Int, Job>()

    private var sharedTickerJob: Job? = null
    private var isForeground = false

    companion object {
        // Actions
        const val ACTION_START_CLOCK_SERVICE = "com.example.purramid.purramidtime.clock.ACTION_START_SERVICE"
        const val ACTION_STOP_CLOCK_SERVICE = "com.example.purramid.purramidtime.clock.ACTION_STOP_SERVICE"
        const val ACTION_ADD_NEW_CLOCK = "com.example.purramid.purramidtime.ACTION_ADD_NEW_CLOCK"
        const val ACTION_UPDATE_CLOCK_SETTING = "com.example.purramid.purramidtime.ACTION_UPDATE_CLOCK_SETTING"
        const val ACTION_NEST_CLOCK = "com.example.purramid.purramidtime.ACTION_NEST_CLOCK"

        private const val TICK_INTERVAL_MS = 100L

        const val EXTRA_CLOCK_ID = "instance_id"
        const val EXTRA_SETTING_TYPE = "setting_type"
        const val EXTRA_SETTING_VALUE = "setting_value"
        const val EXTRA_NEST_STATE = "nest_state"

        private const val TAG = "ClockOverlayService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "ClockOverlayServiceChannel"
        const val MAX_CLOCKS = 4
        const val PREFS_NAME_FOR_ACTIVITY = "clock_service_state_prefs"
        const val KEY_ACTIVE_COUNT_FOR_ACTIVITY = "active_clock_count"
        const val KEY_LAST_INSTANCE_ID = "last_instance_id_clock"

        // Performance thresholds
        private const val MAX_UPDATE_FREQUENCY_MS = 16L
        private const val MEMORY_WARNING_THRESHOLD = 0.8f
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Initialize state manager with service lifecycle scope
        clockStateManager = ClockStateManager(clockRepository, lifecycleScope)

        createNotificationChannel()
        loadAndRestoreClockStates()
    }

    private fun loadAndRestoreClockStates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val persistedStates = clockRepository.loadAllClockStates()

            persistedStates.forEach { entity ->
                instanceManager.registerExistingInstance(InstanceManager.CLOCK, entity.instanceId)

                launch(Dispatchers.Main) {
                    initializeClockInstance(entity)
                }
            }

            if (persistedStates.isNotEmpty()) {
                startForegroundServiceIfNeeded()
                startSharedTicker()
            }

            updateActiveInstanceCountInPrefs()
        }
    }

    private fun initializeClockInstance(entity: ClockStateEntity) {
        // Initialize state in the state manager
        clockStateManager.initializeClock(entity.instanceId, entity)

        // Create the window view
        createAndAddClockWindow(entity.instanceId)

        // Observe state changes
        observeClockState(entity.instanceId)
    }

    // Shared ticker for all clocks
    private fun observeClockState(instanceId: Int) {
        val job = lifecycleScope.launch {
            clockStateManager.clockStates.collectLatest { states ->
                states[instanceId]?.let { state ->
                    updateClockDisplay(instanceId, state)
                }
            }
        }

        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs[instanceId] = job
    }

    private fun updateClockDisplay(instanceId: Int, state: ClockStateManager.ClockRuntimeState) {
        val clockView = clockViewInstances[instanceId] ?: return
        val rootView = activeClockViews[instanceId] ?: return

        // Update clock view
        clockView.apply {
            setInstanceId(instanceId)
            interactionListener = this@ClockOverlayService
            setClockMode(state.mode == "analog")
            setClockColor(state.clockColor)
            setIs24HourFormat(state.is24Hour)
            setClockTimeZone(state.timeZoneId.id)
            setDisplaySeconds(state.displaySeconds)
            setPaused(state.isPaused)
            updateDisplayTime(clockStateManager.getCurrentTimeForClock(instanceId))
        }

        // Update play/pause button
        rootView.findViewById<ImageButton>(R.id.buttonPlayPause)?.apply {
            val isPaused = !currentState.isPaused
            setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
            isActivated = state.isPaused
            imageTintList = ContextCompat.getColorStateList(this@ClockOverlayService, R.color.button_tint_state_list)
        }

        // Update settings button activation if settings are open
        rootView.findViewById<ImageButton>(R.id.buttonSettings)?.apply {
            imageTintList = ContextCompat.getColorStateList(this@ClockOverlayService, R.color.button_tint_state_list)
        }

        // Update reset button
        rootView.findViewById<ImageButton>(R.id.buttonReset)?.apply {
            imageTintList = ContextCompat.getColorStateList(this@ClockOverlayService, R.color.button_tint_state_list)
        }

        // Update nest button state if present
        rootView.findViewById<ImageButton>(R.id.buttonNest)?.apply {
            isActivated = state.isNested
            imageTintList = ContextCompat.getColorStateList(this@ClockOverlayService, R.color.button_tint_state_list)
        }

        // Apply nest mode visuals if state changed
        if (state.isNested) {
            applyNestModeVisuals(instanceId, rootView, true)
        }
    }

    private fun startSharedTicker() {
        if (sharedTickerJob?.isActive == true) return

        sharedTickerJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                // Update all non-paused clocks
                clockStateManager.clockStates.value.forEach { (instanceId, state) ->
                    if (!state.isPaused) {
                        launch(Dispatchers.Main) {
                            val currentTime = clockStateManager.getCurrentTimeForClock(instanceId)
                            clockViewInstances[instanceId]?.updateDisplayTime(currentTime)
                        }
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopSharedTicker() {
        sharedTickerJob?.cancel()
        sharedTickerJob = null
        Log.d(TAG, "Shared ticker cancelled")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        val instanceId = intent?.getIntExtra(EXTRA_CLOCK_ID, 0) ?: 0
        Log.d(TAG, "onStartCommand: Action: $action, instanceId: $instanceId")

        when (action) {
            ACTION_START_CLOCK_SERVICE -> {
                startForegroundServiceIfNeeded()
                if (activeClockViews.isEmpty() && servicePrefs.getInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, 0) == 0) {
                    Log.d(TAG, "No active clocks, adding a new default one.")
                    handleAddNewClockInstance()
                }
            }
            ACTION_ADD_NEW_CLOCK -> {
                startForegroundServiceIfNeeded()
                handleAddNewClockInstance()
            }
            ACTION_UPDATE_CLOCK_SETTING -> {
                if (instanceId > 0) {
                    handleUpdateClockSetting(intent)
                } else {
                    Log.w(TAG, "ACTION_UPDATE_CLOCK_SETTING missing valid instanceId.")
                }
            }
            ACTION_NEST_CLOCK -> {
                if (instanceId > 0) {
                    handleNestClock(intent)
                } else {
                    Log.w(TAG, "ACTION_NEST_CLOCK missing valid instanceId.")
                }
            }
            ACTION_STOP_CLOCK_SERVICE -> {
                stopAllInstancesAndService()
            }
            else -> {
                Log.w(TAG, "Unhandled or null action received: $action")
            }
        }
        return START_STICKY
    }

    private fun handleAddNewClockInstance() {
        if (activeClockViews.size >= MAX_CLOCKS) {
            Log.w(TAG, "Maximum number of clocks ($MAX_CLOCKS) reached.")
            showErrorToUser("Maximum number of clocks reached")
            return
        }

        val newInstanceId = instanceManager.getNextInstanceId(InstanceManager.CLOCK) ?: return

        lifecycleScope.launch {
            // Create default state
            val defaultState = ClockStateEntity(
                instanceId = newInstanceId,
                timeZoneId = ZoneId.systemDefault().id
            )

            // Save to database
            clockRepository.saveClockState(defaultState)

            // Initialize in service
            launch(Dispatchers.Main) {
                initializeClockInstance(defaultState)
            }

            updateActiveInstanceCountInPrefs()

            if (activeClockViews.size == 1) {
                startSharedTicker()
            }
        }
    }

    private fun createAndAddClockWindow(instanceId: Int) {
        try {
            // Inflate layout
            val inflater = LayoutInflater.from(this)
            val clockRootView = inflater.inflate(R.layout.clock_overlay_layout, null) as ViewGroup
            val clockView = clockRootView.findViewById<ClockView>(R.id.clockView)

            // Get current state
            val state = clockStateManager.clockStates.value[instanceId] ?: return

            // Store references
            activeClockViews[instanceId] = clockRootView
            clockViewInstances[instanceId] = clockView

            // Set up layout params
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                gravity = Gravity.TOP or Gravity.START

                if (state.windowWidth > 0 && state.windowHeight > 0) {
                    width = state.windowWidth
                    height = state.windowHeight
                    x = state.windowX
                    y = state.windowY
                } else {
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    // Center on screen by default
                    val displayMetrics = resources.displayMetrics
                    x = displayMetrics.widthPixels / 2 - 200
                    y = displayMetrics.heightPixels / 2 - 150
                }
            }

            clockLayoutParams[instanceId] = params

            // Set up touch handling
            setupWindowDragListener(clockRootView, instanceId)

            // Add view to window manager
            windowManager.addView(clockRootView, params)

            // Set up control buttons
            setupControlButtons(clockRootView, instanceId)

            Log.d(TAG, "Clock window created for instance $instanceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating clock window for instance $instanceId", e)
            cleanupClockInstance(instanceId)
        }
    }

    private fun setupControlButtons(rootView: ViewGroup, instanceId: Int) {
        // Play/Pause button
        rootView.findViewById<ImageButton>(R.id.buttonPlayPause)?.apply {
            setOnClickListener {
                val currentState = clockStateManager.clockStates.value[instanceId] ?: return@setOnClickListener
                clockStateManager.updateClockPaused(instanceId, !currentState.isPaused)

                // Update button appearance
                // TODO Is this still needed?
                setImageResource(if (!isPaused) R.drawable.ic_play else R.drawable.ic_pause)
                isActivated = !isPaused
            }
        }

        // Reset button
        rootView.findViewById<ImageButton>(R.id.buttonReset)?.apply {
            setOnClickListener {
                clockStateManager.resetTime(instanceId)
                // Flash activated state
                isActivated = true
                postDelayed({ isActivated = false }, 200)
            }
        }

        // Settings button
        rootView.findViewById<ImageButton>(R.id.buttonSettings)?.apply {
            setOnClickListener {
                isActivated = true
                val settingsIntent = Intent(this@ClockOverlayService, ClockActivity::class.java).apply {
                    action = ClockActivity.ACTION_SHOW_CLOCK_SETTINGS
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_CLOCK_ID, instanceId)
                }
                startActivity(settingsIntent)

                // Deactivate after delay
                postDelayed({ isActivated = false }, 500)
            }
        }

        // Nest button (if present in layout)
        rootView.findViewById<ImageButton>(R.id.buttonNest)?.apply {
            val isNested = clockStateManager.clockStates.value[instanceId]?.isNested ?: false
            isActivated = isNested

            setOnClickListener {
                val currentNested = clockStateManager.clockStates.value[instanceId]?.isNested ?: false
                clockStateManager.updateClockSettings(instanceId, isNested = !currentNested)
                repositionNestedClocks()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDragListener(view: View, instanceId: Int) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false
        var isResizing = false
        var initialWidth = 0
        var initialHeight = 0

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // Scale gesture detector for pinch-to-resize
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val clockView = clockViewInstances[instanceId]
                if (clockView?.isHandDragging() == true) {
                    return false // Don't resize if hand is being dragged
                }

                if (!isMoving) {
                    isResizing = true
                    val params = clockLayoutParams[instanceId] ?: return false
                    initialWidth = params.width
                    initialHeight = params.height
                    return true
                }
                return false
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isResizing) {
                    val params = clockLayoutParams[instanceId] ?: return false
                    val scaleFactor = detector.scaleFactor

                    val minSize = dpToPx(100)
                    val maxSize = dpToPx(500)

                    val newWidth = (initialWidth * scaleFactor).toInt()
                        .coerceIn(minSize, maxSize)
                    val newHeight = (initialHeight * scaleFactor).toInt()
                        .coerceIn(minSize, maxSize)

                    params.width = newWidth
                    params.height = newHeight

                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                            clockStateManager.updateWindowSize(instanceId, newWidth, newHeight)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resizing window $instanceId", e)
                    }
                    return true
                }
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isResizing = false
            }
        })

        view.setOnTouchListener { _, event ->
            val params = clockLayoutParams[instanceId] ?: return@setOnTouchListener false

            // Handle scale gestures first
            if (scaleGestureDetector.onTouchEvent(event)) {
                return@setOnTouchListener true
            }

            // Check if the touch is on the ClockView itself
            val clockView = clockViewInstances[instanceId]
            if (clockView != null) {
                val location = IntArray(2)
                clockView.getLocationOnScreen(location)
                val clockX = event.rawX - location[0]
                val clockY = event.rawY - location[1]

                if (clockX >= 0 && clockX <= clockView.width &&
                    clockY >= 0 && clockY <= clockView.height) {
                    // Let ClockView handle its own touch events
                    return@setOnTouchListener false
                }
            }

            // Handle window dragging
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    isResizing = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        return@setOnTouchListener true
                    }

                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isMoving && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        isMoving = true
                    }

                    if (isMoving) {
                        val displayMetrics = resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - params.width
                        val maxY = displayMetrics.heightPixels - params.height

                        params.x = (initialX + deltaX.toInt()).coerceIn(0, maxX)
                        params.y = (initialY + deltaY.toInt()).coerceIn(0, maxY)

                        try {
                            if (view.isAttachedToWindow) {
                                windowManager.updateViewLayout(view, params)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error moving window $instanceId", e)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isMoving) {
                        clockStateManager.updateWindowPosition(instanceId, params.x, params.y)
                    }
                    isMoving = false
                    isResizing = false
                    true
                }

                else -> false
            }
        }
    }

    override fun onTimeManuallySet(instanceId: Int, newTime: LocalTime) {
        Log.d(TAG, "Manual time set for clock $instanceId: $newTime")
        clockStateManager.setManualTime(instanceId, newTime)
    }

    override fun onDragStateChanged(instanceId: Int, isDragging: Boolean) {
        Log.d(TAG, "Clock $instanceId drag state changed: $isDragging")

        val params = clockLayoutParams[instanceId] ?: return
        val clockRootView = activeClockViews[instanceId] ?: return

        if (isDragging) {
            // Disable window dragging while hand is being dragged
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            // Visual feedback for drag state
            clockRootView.alpha = 0.9f
            // Pause the clock during drag
            clockStateManager.updateClockPaused(instanceId, true)
        } else {
            // Re-enable window interaction
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            // Restore full opacity
            clockRootView.alpha = 1.0f
        }

        // Apply the updated params
        try {
            if (clockRootView.isAttachedToWindow) {
                windowManager.updateViewLayout(clockRootView, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window flags during drag state change", e)
        }
    }

    private fun handleUpdateClockSetting(intent: Intent?) {
        val instanceId = intent?.getIntExtra(EXTRA_CLOCK_ID, -1) ?: -1
        val settingType = intent?.getStringExtra(EXTRA_SETTING_TYPE)

        if (instanceId <= 0 || settingType == null) {
            Log.e(TAG, "Cannot update setting, invalid instanceId ($instanceId) or missing ViewModel/settingType.")
            return
        }
        Log.d(TAG, "Updating setting '$settingType' for clock $instanceId")
        when (settingType) {
            "mode" -> clockStateManager.updateClockSettings(
                instanceId,
                mode = intent.getStringExtra(EXTRA_SETTING_VALUE) ?: "digital"
            )
            "color" -> clockStateManager.updateClockSettings(
                instanceId,
                color = intent.getIntExtra(EXTRA_SETTING_VALUE, android.graphics.Color.WHITE)
            )
            "24hour" -> clockStateManager.updateClockSettings(
                instanceId,
                is24Hour = intent.getBooleanExtra(EXTRA_SETTING_VALUE, false)
            )
            "time_zone" -> {
                val zoneIdString = intent.getStringExtra(EXTRA_SETTING_VALUE)
                clockStateManager.updateClockSettings(instanceId, timeZoneId = zoneIdString)
            }
            "seconds" -> clockStateManager.updateClockSettings(
                instanceId,
                displaySeconds = intent.getBooleanExtra(EXTRA_SETTING_VALUE, true)
            )
            else -> Log.w(TAG, "Unknown setting type: $settingType")
        }
    }

    private fun handleNestClock(intent: Intent?) {
        val instanceId = intent?.getIntExtra(EXTRA_CLOCK_ID, -1) ?: -1
        val shouldBeNested = intent?.getBooleanExtra(EXTRA_NEST_STATE, false) ?: false

        if (instanceId > 0) {
            Log.d(TAG, "Setting nest state for clock $instanceId to $shouldBeNested")
            clockStateManager.updateClockSettings(instanceId, isNested = shouldBeNested)
            repositionNestedClocks()
        } else {
            Log.e(TAG, "Invalid instanceId ($instanceId) for ACTION_NEST_CLOCK.")
        }
    }

    private fun repositionNestedClocks() {
        Log.d(TAG, "Repositioning nested clocks in columnized layout")

        val nestedClocks = clockStateManager.clockStates.value.entries
            .filter { it.value.isNested }
            .sortedBy { it.key } // Sort by instance ID (older instances have lower IDs)
            .map { it.key }

        if (nestedClocks.isEmpty()) return

        val displayMetrics = DisplayMetrics().also {
            windowManager.defaultDisplay.getMetrics(it)
        }
        val padding = dpToPx(20)
        val spacing = dpToPx(10)
        val clockHeight = dpToPx(75)

        val startX = displayMetrics.widthPixels - dpToPx(75) - padding
        val startY = padding

        nestedClocks.forEachIndexed { index, instanceId ->
            val clockRootView = activeClockViews[instanceId] ?: return@forEachIndexed
            val params = clockLayoutParams[instanceId] ?: return@forEachIndexed

            // Stack from top to bottom with newest (highest instance ID) at bottom
            val yOffset = index * (clockHeight + spacing)
            val targetY = startY + yOffset

            val maxY = displayMetrics.heightPixels - clockHeight - padding
            val finalY = minOf(targetY, maxY)

            params.x = startX
            params.y = finalY

            try {
                if (clockRootView.isAttachedToWindow) {
                    windowManager.updateViewLayout(clockRootView, params)
                    clockStateManager.updateWindowPosition(instanceId, params.x, params.y)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error repositioning nested clock $instanceId", e)
            }
        }
    }

    private fun applyNestModeVisuals(instanceId: Int, clockRootView: ViewGroup, isNested: Boolean) {
        Log.d(TAG, "Applying nest visuals for $instanceId: $isNested")

        val params = clockLayoutParams[instanceId] ?: return

        if (isNested) {
            val state = clockStateManager.clockStates.value[instanceId] ?: return
            val isAnalog = state.mode == "analog"

            params.width = dpToPx(75)
            params.height = if (isAnalog) dpToPx(75) else dpToPx(50)

            // Hide control buttons
            clockRootView.findViewById<View>(R.id.buttonPlayPause)?.visibility = View.GONE
            clockRootView.findViewById<View>(R.id.buttonReset)?.visibility = View.GONE
            clockRootView.findViewById<View>(R.id.buttonSettings)?.visibility = View.GONE
            clockRootView.findViewById<View>(R.id.buttonNest)?.visibility = View.GONE

        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT

            // Show control buttons
            clockRootView.findViewById<View>(R.id.buttonPlayPause)?.visibility = View.VISIBLE
            clockRootView.findViewById<View>(R.id.buttonReset)?.visibility = View.VISIBLE
            clockRootView.findViewById<View>(R.id.buttonSettings)?.visibility = View.VISIBLE
            clockRootView.findViewById<View>(R.id.buttonNest)?.visibility = View.VISIBLE
        }

        try {
            if (clockRootView.isAttachedToWindow) {
                windowManager.updateViewLayout(clockRootView, params)
                clockStateManager.updateWindowSize(instanceId, params.width, params.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying nest mode visuals", e)
        }
    }

    private fun removeClockInstance(instanceId: Int) {
        lifecycleScope.launch {
            // Clean up views
            val viewToRemove = activeClockViews[instanceId]
            if (viewToRemove?.isAttachedToWindow == true) {
                try {
                    windowManager.removeView(viewToRemove)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing view for instance $instanceId", e)
                }
            }

            // Clean up references
            activeClockViews.remove(instanceId)
            clockViewInstances.remove(instanceId)
            clockLayoutParams.remove(instanceId)

            // Cancel observers
            stateObserverJobs[instanceId]?.cancel()
            stateObserverJobs.remove(instanceId)

            // Remove from state manager and database
            clockStateManager.removeClock(instanceId)

            // Release instance ID
            instanceManager.releaseInstanceId(InstanceManager.CLOCK, instanceId)

            updateActiveInstanceCountInPrefs()

            if (activeClockViews.isEmpty()) {
                stopSharedTicker()
                stopService()
            }
        }
    }

    private fun cleanupClockInstance(instanceId: Int) {
        Log.d(TAG, "Cleaning up clock instance: $instanceId")

        stateObserverJobs[instanceId]?.cancel()
        stateObserverJobs.remove(instanceId)

        val view = activeClockViews[instanceId]
        if (view != null) {
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view during cleanup", e)
            }
            activeClockViews.remove(instanceId)
       }

        clockViewInstances.remove(instanceId)
        instanceManager.releaseInstanceId(InstanceManager.CLOCK, instanceId)
    }

    private fun stopAllInstancesAndService() {
        Log.d(TAG, "Stopping all instances and Clock service.")
        activeClockViews.keys.toList().forEach { id ->
            removeClockInstance(id)
        }
    }

    private fun stopService() {
        Log.d(TAG, "stopService called for Clock")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isForeground = false
    }

    private fun startForegroundServiceIfNeeded() {
        if (isForeground) return
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "ClockOverlayService started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service for Clock", e)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Floating clock(s) active")
            .setSmallIcon(R.drawable.ic_clock_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Clock Overlay Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateActiveInstanceCountInPrefs() {
        servicePrefs.edit().putInt(KEY_ACTIVE_COUNT_FOR_ACTIVITY, activeClockViews.size).apply()
        Log.d(TAG, "Updated active Clock count: ${activeClockViews.size}")
    }

    private fun showErrorToUser(message: String) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            // The activity will show the error message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show error message to user", e)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - cleaning up resources")
        stopSharedTicker()
        cleanupAllResources()
        super.onDestroy()
    }

    private fun cleanupAllResources() {
        stateObserverJobs.values.forEach { it.cancel() }
        stateObserverJobs.clear()

        activeClockViews.forEach { (instanceId, view) ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view during cleanup for instance $instanceId", e)
            }
        }
        activeClockViews.clear()
        clockViewInstances.clear()
        clockLayoutParams.clear()

        Log.d(TAG, "Resource cleanup completed")
    }
}