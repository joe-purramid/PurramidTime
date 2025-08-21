// ClockViewModel.kt
package com.example.purramid.purramidtime.clock.viewmodel

import android.graphics.Color
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.purramidtime.data.db.ClockDao
import com.example.purramid.purramidtime.data.db.ClockStateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

// --- ClockState Data Class (keep as defined previously) ---
data class ClockState(
    val instanceId: Int,
    val timeZoneId: ZoneId = ZoneId.systemDefault(),
    val isPaused: Boolean = false,
    val displaySeconds: Boolean = true,
    val is24Hour: Boolean = false,
    val clockColor: Int = Color.WHITE,
    val mode: String = "digital", // "digital" or "analog"
    val isNested: Boolean = false,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,
    val currentTime: LocalTime = LocalTime.now(),
    val manuallySetTime: LocalTime? = null
)


@HiltViewModel
class ClockViewModel @Inject constructor(
    private val clockDao: ClockDao,
) : ViewModel() {

    companion object {
        const val KEY_INSTANCE_ID = "instanceId"
        private const val TAG = "ClockViewModel"
        private const val TICK_INTERVAL_MS = 100L
    }

    private var instanceId: Int = -1
    private lateinit var defaultState: ClockState

    private val _uiState = MutableStateFlow<ClockState?>(null)
    val uiState: StateFlow<ClockState> = _uiState.asStateFlow()

    init {
        // Don't load state here - wait for initialize()
        Log.d(TAG, "ViewModel created, waiting for initialization")
    }

    /**
     * Initialize the ViewModel with a specific instance ID.
     * This must be called immediately after ViewModel creation.
     */
    fun initialize(instanceId: Int) {
        if (this.instanceId != -1) {
            Log.w(TAG, "ViewModel already initialized with instanceId: ${this.instanceId}")
            return
        }

        Log.d(TAG, "Initializing ViewModel for instanceId: $instanceId")
        this.instanceId = instanceId
        this.defaultState = ClockState(instanceId = instanceId)

        loadInitialState(instanceId)
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = clockDao.getByInstanceId(id)
            withContext(Dispatchers.Main) {
                val initialState = if (entity != null) {
                    Log.d(TAG, "Loaded state from DB for instance $id")
                    mapEntityToState(entity)
                } else {
                    Log.w(TAG, "No saved state found for instance $id, initializing with defaults.")
                    ClockState(instanceId = id, currentTime = LocalTime.now(ZoneId.systemDefault())).also {
                        saveState(it)
                    }
                }
                _uiState.value = initialState
            }
        }
    }

    /**
     * Updates the current time from an external ticker.
     * Called by the service's shared ticker.
     */
    fun updateTimeFromTicker(currentTimeMillis: Long) {
        if (_uiState.value?.isPaused == true) {
            // Don't update time if paused
            return
        }

        _uiState.update { currentState ->
            currentState?.let { state ->
                val timeZone = state.timeZoneId
                val newTime = LocalTime.now(timeZone)
                state.copy(currentTime = newTime)
            }
        }
    }

    // --- State Update Actions ---
    /**
     * Pauses or resumes the clock's timekeeping.
     * When resuming, the clock continues from the time it was paused at.
     */
    fun setPaused(shouldPause: Boolean) {
        if (_uiState.value?.isPaused == shouldPause) return

        _uiState.update { currentState ->
            currentState?.let { state ->
                val manualTime = if (!shouldPause && state.manuallySetTime != null) null else state.manuallySetTime
                state.copy(isPaused = shouldPause, manuallySetTime = manualTime)
            }
        }

        saveState(_uiState.value) // Save the paused state
    }

    /**
     * Resets the clock to the current actual time in its time zone,
     * applying any time deficit or surplus from manual adjustment or pausing.
     */
    fun resetTime() {
        _uiState.update { currentState ->
            currentState?.let { state ->
                val actualCurrentTime = LocalTime.now(state.timeZoneId)

                // If there was a manually set time or the clock was paused,
                // calculate the time deficit/surplus and apply it
                val adjustedTime = if (state.isPaused && state.manuallySetTime != null) {
                    // Calculate the difference between manually set time and actual time
                    val timeDifference = java.time.Duration.between(state.manuallySetTime, actualCurrentTime)
                    // Apply the correction to restore actual time
                    actualCurrentTime
                } else if (state.isPaused) {
                    // If paused without manual time, just restore to current time
                    actualCurrentTime
                } else {
                    // If running, no adjustment needed
                    actualCurrentTime
                }

                state.copy(
                    currentTime = adjustedTime,
                    isPaused = false,
                    manuallySetTime = null
                )
            }
        }
        saveState(_uiState.value)
    }
    /**
     * Sets a specific time, usually from user interaction (dragging hands).
     * This action implies the clock should be paused.
     */
    fun setManuallySetTime(manualTime: LocalTime) {
        // Ensure clock is paused before accepting manual time
        _uiState.value?.isPaused?.let {
            if (!it) {
                Log.d(TAG, "Pausing clock $instanceId before setting manual time.")
                stopTicker() // Stop ticker explicitly
            }
        }
        _uiState.update {
            it?.copy(
                manuallySetTime = manualTime,
                currentTime = manualTime, // Update display time immediately
                isPaused = true // Ensure it's marked as paused
            )
        }
        saveState(_uiState.value) // Save the manually set time and paused state
    }

    // --- Settings Updates (Remain the same, call saveState) ---
    fun updateMode(newMode: String) {
        if (_uiState.value?.mode == newMode) return
        val resetTime = LocalTime.now(_uiState.value?.timeZoneId)
        _uiState.update { it?.copy(mode = newMode, currentTime = resetTime, manuallySetTime = null, isPaused = false) }
        startTicker()
        saveState(_uiState.value)
    }
    fun updateColor(newColor: Int) {
        if (_uiState.value?.clockColor == newColor) return
        _uiState.update { it?.copy(clockColor = newColor) }
        saveState(_uiState.value)
    }
    fun updateIs24Hour(is24: Boolean) {
        if (_uiState.value?.is24Hour == is24) return
        _uiState.update { it?.copy(is24Hour = is24) }
        saveState(_uiState.value)
    }
    fun updateTimeZone(zoneId: ZoneId) {
        if (_uiState.value?.timeZoneId == zoneId) return
        val newTime = LocalTime.now(zoneId)
        _uiState.update { it?.copy(timeZoneId = zoneId, currentTime = newTime, manuallySetTime = null) }
        saveState(_uiState.value)
    }
    fun updateDisplaySeconds(display: Boolean) {
        if (_uiState.value?.displaySeconds == display) return
        _uiState.update { it?.copy(displaySeconds = display) }
        saveState(_uiState.value)
    }
    fun updateIsNested(isNested: Boolean) {
        if (_uiState.value?.isNested == isNested) return
        _uiState.update { it?.copy(isNested = isNested) }
        saveState(_uiState.value)
    }
    fun updateWindowPosition(x: Int, y: Int) {
        if (_uiState.value?.windowX == x && _uiState.value.windowY == y) return
        _uiState.update { it?.copy(windowX = x, windowY = y) }
        saveState(_uiState.value)
    }
    fun updateWindowSize(width: Int, height: Int) {
        if (_uiState.value?.windowWidth == width && _uiState.value?.windowHeight == height) return
        _uiState.update { it?.copy(windowWidth = width, windowHeight = height) }
        saveState(_uiState.value)
    }

    // --- Persistence (Remains the same) ---
    fun deleteState() {
        if (instanceId == -1) {
            Log.w(TAG, "Cannot delete state, invalid instanceId: $instanceId")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                clockDao.deleteByInstanceId(instanceId)
                Log.d(TAG, "Deleted state for clock $instanceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete state for clock $instanceId", e)
            }
        }
    }

    // --- Mappers (Remain the same) ---
    private fun mapEntityToState(entity: ClockStateEntity): ClockState {
        val timeZone = try {
            ZoneId.of(entity.timeZoneId)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid timezone ID: ${entity.timeZoneId}, using system default", e)
            ZoneId.systemDefault()
        }

        val manualTime = entity.manuallySetTimeSeconds?.let {
            try {
                LocalTime.ofSecondOfDay(it % (24 * 3600))
            } catch (e: Exception) {
                Log.e(TAG, "Invalid manual time seconds: $it", e)
                null
            }
        }

        // If paused and has manual time, use that. Otherwise use current time for the zone.
        val currentTime = if (entity.isPaused && manualTime != null) {
            manualTime
        } else {
            LocalTime.now(timeZone)
        }

        return ClockState(
            instanceId = entity.instanceId,
            timeZoneId = timeZone,
            isPaused = entity.isPaused,
            displaySeconds = entity.displaySeconds,
            is24Hour = entity.is24Hour,
            clockColor = entity.clockColor,
            mode = entity.mode,
            isNested = entity.isNested,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight,
            currentTime = currentTime.truncatedTo(ChronoUnit.NANOS),
            manuallySetTime = manualTime
        )
    }

    private fun mapStateToEntity(state: ClockState): ClockStateEntity {
        // Determine what time to persist
        val timeToPersist = when {
            state.manuallySetTime != null -> state.manuallySetTime
            state.isPaused -> state.currentTime
            else -> null // Don't persist time if running normally
        }

        val timeToPersistSeconds = timeToPersist?.toSecondOfDay()?.toLong()

        return ClockStateEntity(
            instanceId = state.instanceId,
            uuid = UUID.randomUUID(), // Generate new UUID if needed
            timeZoneId = state.timeZoneId.id,
            isPaused = state.isPaused,
            displaySeconds = state.displaySeconds,
            is24Hour = state.is24Hour,
            clockColor = state.clockColor,
            mode = state.mode,
            isNested = state.isNested,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight,
            manuallySetTimeSeconds = timeToPersistSeconds
        )
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for instanceId: $instanceId")
        // No ticker to stop
        super.onCleared()
    }

    // Persistence
    private fun saveState(state: ClockState) {
        val currentId = state.instanceId
        if (currentId == -1) {
            Log.w(TAG, "Cannot save state, invalid instanceId: $currentId")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                clockDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for clock $currentId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for clock $currentId", e)
            }
        }
    }

    private fun mapStateToEntity(state: ClockState): ClockStateEntity {
        // Store the time that should persist when paused (either manually set or the time it was paused at)
        val timeToPersist = state.manuallySetTime ?: state.currentTime
        val timeToPersistSeconds = timeToPersist.toSecondOfDay().toLong()

        return ClockStateEntity(
            instanceId = state.instanceId,
            timeZoneId = state.timeZoneId.id,
            isPaused = state.isPaused,
            displaySeconds = state.displaySeconds,
            is24Hour = state.is24Hour,
            clockColor = state.clockColor,
            mode = state.mode,
            isNested = state.isNested,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight,
            // Save the manual time if set, otherwise save the current displayed time IF PAUSED
            manuallySetTimeSeconds = if (state.isPaused) timeToPersistSeconds else null
        )
    }

    private fun startTicker() {
        // Note: In the current architecture, the ticker is managed by the Service's
        // shared ticker, not by individual ViewModels. This function is kept for
        // compatibility but doesn't need to do anything.
        Log.d(TAG, "startTicker called - ticker is managed by service")
    }

    private fun stopTicker() {
        // Note: In the current architecture, the ticker is managed by the Service's
        // shared ticker, not by individual ViewModels. This function is kept for
        // compatibility but doesn't need to do anything.
        Log.d(TAG, "stopTicker called - ticker is managed by service")
    }

    // Cleanup
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for instanceId: $instanceId")
        stopTicker()
        super.onCleared()
    }

}
