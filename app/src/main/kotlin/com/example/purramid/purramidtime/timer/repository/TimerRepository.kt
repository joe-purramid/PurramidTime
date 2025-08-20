package com.example.purramid.purramidtime.timer.repository

import android.os.SystemClock
import android.util.Log
import com.example.purramid.purramidtime.data.db.TimerDao
import com.example.purramid.purramidtime.data.db.TimerStateEntity
import com.example.purramid.purramidtime.di.IoDispatcher
import com.example.purramid.purramidtime.timer.MusicUrlManager
import com.example.purramid.purramidtime.timer.PresetTimesManager
import com.example.purramid.purramidtime.timer.TimerState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimerRepository @Inject constructor(
    private val timerDao: TimerDao,
    private val musicUrlManager: MusicUrlManager,
    private val presetTimesManager: PresetTimesManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "TimerRepository"
        private const val TICK_INTERVAL_MS = 50L
    }

    private val gson = Gson()

    // Store active timer states in memory for quick access
    private val activeTimerStates = mutableMapOf<Int, MutableStateFlow<TimerState>>()
    private val tickerJobs = mutableMapOf<Int, Job?>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Get or create a timer state flow for a specific timer ID
     */
    fun getTimerStateFlow(timerId: Int): StateFlow<TimerState> {
        return activeTimerStates.getOrPut(timerId) {
            MutableStateFlow(TimerState(timerId = timerId))
        }.asStateFlow()
    }

    /**
     * Initialize timer state from database or create new
     */
    suspend fun initializeTimer(timerId: Int): TimerState = withContext(ioDispatcher) {
        try {
            val entity = timerDao.getById(timerId)
            val state = if (entity != null) {
                Log.d(TAG, "Loaded state from DB for timer $timerId")
                mapEntityToState(entity).copy(
                    recentMusicUrls = musicUrlManager.getRecentUrls(),
                    presetTimes = presetTimesManager.getPresetTimes()
                )
            } else {
                Log.d(TAG, "No saved state for timer $timerId, using defaults.")
                TimerState(
                    timerId = timerId,
                    uuid = UUID.randomUUID(),
                    presetTimes = presetTimesManager.getPresetTimes(),
                    recentMusicUrls = musicUrlManager.getRecentUrls()
                )
            }

            // Update the flow
            activeTimerStates.getOrPut(timerId) {
                MutableStateFlow(state)
            }.value = state

            // Save if new
            if (entity == null) {
                saveState(state)
            }

            // Start ticker if running
            if (state.isRunning) {
                startTicker(timerId)
            }

            state
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial state for timer $timerId", e)
            val defaultState = TimerState(
                timerId = timerId,
                uuid = UUID.randomUUID(),
                presetTimes = presetTimesManager.getPresetTimes(),
                recentMusicUrls = musicUrlManager.getRecentUrls()
            )
            activeTimerStates.getOrPut(timerId) {
                MutableStateFlow(defaultState)
            }.value = defaultState
            defaultState
        }
    }

    /**
     * Get current timer state
     */
    fun getCurrentState(timerId: Int): TimerState {
        return activeTimerStates[timerId]?.value ?: TimerState(timerId = timerId)
    }

    /**
     * Update timer state
     */
    suspend fun updateState(timerId: Int, update: (TimerState) -> TimerState) {
        val stateFlow = activeTimerStates.getOrPut(timerId) {
            MutableStateFlow(TimerState(timerId = timerId))
        }

        val newState = update(stateFlow.value)
        stateFlow.value = newState
        saveState(newState)
    }

    // --- Timer Controls ---

    suspend fun togglePlayPause(timerId: Int) {
        val stateFlow = activeTimerStates[timerId] ?: return
        val currentState = stateFlow.value

        if (currentState.currentMillis <= 0L) {
            return // Don't start countdown if already finished
        }

        val newRunningState = !currentState.isRunning

        updateState(timerId) { it.copy(isRunning = newRunningState) }

        if (newRunningState) {
            startTicker(timerId)
        } else {
            stopTicker(timerId)
        }
    }

    suspend fun resetTimer(timerId: Int) {
        stopTicker(timerId)
        updateState(timerId) { state ->
            state.copy(
                isRunning = false,
                currentMillis = state.initialDurationMillis
            )
        }
    }

    suspend fun setInitialDuration(timerId: Int, durationMillis: Long) {
        val currentState = getCurrentState(timerId)
        if (!currentState.isRunning) {
            updateState(timerId) { it.copy(
                initialDurationMillis = durationMillis,
                currentMillis = durationMillis
            )}
        }
    }

    suspend fun setPlaySoundOnEnd(timerId: Int, play: Boolean) {
        updateState(timerId) { it.copy(playSoundOnEnd = play) }
    }

    suspend fun updateOverlayColor(timerId: Int, color: Int) {
        updateState(timerId) { it.copy(overlayColor = color) }
    }

    suspend fun updateWindowPosition(timerId: Int, x: Int, y: Int) {
        updateState(timerId) { it.copy(windowX = x, windowY = y) }
    }

    suspend fun updateWindowSize(timerId: Int, width: Int, height: Int) {
        updateState(timerId) { it.copy(windowWidth = width, windowHeight = height) }
    }

    suspend fun setNested(timerId: Int, nested: Boolean) {
        updateState(timerId) { state ->
            state.copy(
                isNested = nested,
                nestedX = if (nested) state.nestedX else -1,
                nestedY = if (nested) state.nestedY else -1
            )
        }
    }

    suspend fun updateNestedPosition(timerId: Int, x: Int, y: Int) {
        updateState(timerId) { it.copy(nestedX = x, nestedY = y) }
    }

    suspend fun setSelectedSound(timerId: Int, uri: String?) {
        updateState(timerId) { it.copy(selectedSoundUri = uri) }
    }

    suspend fun setMusicUrl(timerId: Int, url: String?) {
        url?.let { musicUrlManager.addRecentUrl(it) }

        updateState(timerId) { state ->
            state.copy(
                musicUrl = url,
                recentMusicUrls = musicUrlManager.getRecentUrls()
            )
        }
    }

    suspend fun loadPresetDuration(timerId: Int, durationMillis: Long) {
        setInitialDuration(timerId, durationMillis)
    }

    suspend fun refreshPresetTimes(timerId: Int) {
        updateState(timerId) { state ->
            state.copy(presetTimes = presetTimesManager.getPresetTimes())
        }
    }

    // --- Ticker Logic ---

    private fun startTicker(timerId: Int) {
        // Cancel existing ticker if any
        tickerJobs[timerId]?.cancel()

        val stateFlow = activeTimerStates[timerId] ?: return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = stateFlow.value.currentMillis

        tickerJobs[timerId] = coroutineScope.launch {
            while (isActive && stateFlow.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = (initialMillis - elapsed).coerceAtLeast(0L)

                stateFlow.update { it.copy(currentMillis = newMillis) }

                if (newMillis <= 0L) {
                    handleCountdownFinish(timerId)
                    break
                }

                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for timer $timerId")
        }
        Log.d(TAG, "Ticker starting for timer $timerId...")
    }

    private fun stopTicker(timerId: Int) {
        tickerJobs[timerId]?.cancel()
        tickerJobs[timerId] = null
        Log.d(TAG, "Ticker stopped for timer $timerId")
    }

    private suspend fun handleCountdownFinish(timerId: Int) {
        stopTicker(timerId)
        updateState(timerId) { state ->
            state.copy(
                isRunning = false,
                currentMillis = 0,
                // Always un-nest when countdown finishes
                isNested = false,
                nestedX = -1,
                nestedY = -1
            )
        }
        Log.d(TAG, "Timer $timerId finished.")
    }

    // --- Persistence ---

    private suspend fun saveState(state: TimerState) = withContext(ioDispatcher) {
        if (state.timerId <= 0) {
            Log.w(TAG, "Attempted to save state with invalid timerId: ${state.timerId}")
            return@withContext
        }

        try {
            val entity = mapStateToEntity(state)
            timerDao.insertOrUpdate(entity)
            Log.d(TAG, "Saved state for timer ${state.timerId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state for timer ${state.timerId}", e)
        }
    }

    suspend fun deleteTimer(timerId: Int) = withContext(ioDispatcher) {
        stopTicker(timerId)
        activeTimerStates.remove(timerId)
        tickerJobs.remove(timerId)

        try {
            timerDao.deleteById(timerId)
            Log.d(TAG, "Deleted state for timer $timerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete state for timer $timerId", e)
        }
    }

    // --- Cleanup ---

    fun cleanup() {
        tickerJobs.values.forEach { it?.cancel() }
        tickerJobs.clear()
        activeTimerStates.clear()
        coroutineScope.cancel()
    }

    // --- Mappers ---

    private fun mapEntityToState(entity: TimerStateEntity): TimerState {
        val recentUrlsList = try {
            val typeToken = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.recentMusicUrlsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recent URLs JSON", e)
            emptyList()
        }

        return TimerState(
            timerId = entity.timerId,
            uuid = try {
                UUID.fromString(entity.uuid)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid UUID in entity", e)
                UUID.randomUUID()
            },
            initialDurationMillis = entity.initialDurationMillis,
            currentMillis = entity.currentMillis,
            isRunning = entity.isRunning,
            playSoundOnEnd = entity.playSoundOnEnd,
            overlayColor = entity.overlayColor,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight,
            isNested = entity.isNested,
            nestedX = entity.nestedX,
            nestedY = entity.nestedY,
            selectedSoundUri = entity.selectedSoundUri,
            musicUrl = entity.musicUrl,
            recentMusicUrls = recentUrlsList,
            presetTimes = presetTimesManager.getPresetTimes(),
            showPresetButton = entity.showPresetButton
        )
    }

    private fun mapStateToEntity(state: TimerState): TimerStateEntity {
        val recentUrlsJson = gson.toJson(state.recentMusicUrls)

        return TimerStateEntity(
            timerId = state.timerId,
            uuid = state.uuid.toString(),
            initialDurationMillis = state.initialDurationMillis,
            currentMillis = state.currentMillis,
            isRunning = state.isRunning,
            playSoundOnEnd = state.playSoundOnEnd,
            overlayColor = state.overlayColor,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight,
            isNested = state.isNested,
            nestedX = state.nestedX,
            nestedY = state.nestedY,
            selectedSoundUri = state.selectedSoundUri,
            musicUrl = state.musicUrl,
            recentMusicUrlsJson = recentUrlsJson,
            showPresetButton = state.showPresetButton
        )
    }
}