// TimerViewModel.kt
package com.example.purramid.purramidtime.timer.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.purramidtime.data.db.TimerDao
import com.example.purramid.purramidtime.data.db.TimerStateEntity
import com.example.purramid.purramidtime.timer.MusicUrlManager
import com.example.purramid.purramidtime.timer.PresetTime
import com.example.purramid.purramidtime.timer.PresetTimesManager
import com.example.purramid.purramidtime.timer.TimerState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val timerDao: TimerDao,
    private val savedStateHandle: SavedStateHandle,
    private val musicUrlManager: MusicUrlManager,
    private val presetTimesManager: PresetTimesManager
) : ViewModel() {

    companion object {
        const val KEY_TIMER_ID = "timerId"
        private const val TAG = "TimerViewModel"
        private const val TICK_INTERVAL_MS = 50L
    }

    // Initialize timerId - will be set by setTimerId() from Service
    private var timerId: Int = 0

    private val _uiState = MutableStateFlow(TimerState(timerId = timerId))
    val uiState: StateFlow<TimerState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private val gson = Gson()

    init {
        Log.d(TAG, "Initializing ViewModel")
        // TimerId will be set by the Service
    }

    fun setTimerId(id: Int) {
        if (timerId == 0 && id > 0) {
            timerId = id
            savedStateHandle[KEY_TIMER_ID] = id
            loadInitialState(id)
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = timerDao.getById(id)
                withContext(Dispatchers.Main) {
                    if (entity != null) {
                        Log.d(TAG, "Loaded state from DB for timer $id")
                        val state = mapEntityToState(TimerStateEntity)
                        // Override with global recent URLs
                        _uiState.value = state.copy(
                            recentMusicUrls = musicUrlManager.getRecentUrls()
                            presetTimes = presetTimesManager.getPresetTimes()
                        )
                        if (_uiState.value.isRunning) {
                            startTicker()
                        }
                    } else {
                        Log.d(TAG, "No saved state for timer $id, using defaults.")
                        val defaultState = TimerState(
                            timerId = id,
                            uuid = UUID.randomUUID()
                            presetTimes = presetTimesManager.getPresetTimes()
                        )
                        _uiState.value = defaultState
                        saveState(defaultState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state for timer $id", e)
                withContext(Dispatchers.Main) {
                    val defaultState = TimerState(
                        timerId = id,
                        uuid = UUID.randomUUID()
                        presetTimes = presetTimesManager.getPresetTimes()
                    )
                    _uiState.value = defaultState
                }
            }
        }
    }

    // --- Timer Controls ---

    fun togglePlayPause() {
        val currentState = _uiState.value
        if (currentState.currentMillis <= 0L) {
            return // Don't start countdown if already finished
        }

        val newRunningState = !currentState.isRunning
        _uiState.update { it.copy(isRunning = newRunningState) }

        if (newRunningState) {
            startTicker()
        } else {
            stopTicker()
        }
        saveState(_uiState.value)
    }

    fun resetTimer() {
        stopTicker()
        _uiState.update {
            it.copy(
                isRunning = false,
                currentMillis = it.initialDurationMillis
            )
        }
        saveState(_uiState.value)
    }

    // --- Ticker Logic ---
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        val startTime = SystemClock.elapsedRealtime()
        val initialMillis = _uiState.value.currentMillis

        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && _uiState.value.isRunning) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val newMillis = (initialMillis - elapsed).coerceAtLeast(0L)

                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(currentMillis = newMillis) }
                }

                if (newMillis <= 0L) {
                    handleCountdownFinish()
                    break
                }

                delay(TICK_INTERVAL_MS)
            }
            Log.d(TAG, "Ticker coroutine ended for timer $timerId")
        }
        Log.d(TAG, "Ticker starting for timer $timerId...")
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        Log.d(TAG, "Ticker stopped for timer $timerId")
    }

    private fun handleCountdownFinish() {
        stopTicker()
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    isRunning = false,
                    currentMillis = 0,
                    // Always un-nest when countdown finishes
                    isNested = false,
                    nestedX = -1,
                    nestedY = -1
                )
            }
            Log.d(TAG, "Timer $timerId finished.")
            saveState(_uiState.value)
        }
    }

    // --- Settings Updates ---
    fun setInitialDuration(durationMillis: Long) {
        if (!_uiState.value.isRunning) {
            _uiState.update { it.copy(initialDurationMillis = durationMillis, currentMillis = durationMillis) }
            saveState(_uiState.value)
        }
    }

    fun setPlaySoundOnEnd(play: Boolean) {
        if (_uiState.value.playSoundOnEnd == play) return
        _uiState.update { it.copy(playSoundOnEnd = play) }
        saveState(_uiState.value)
    }

    fun updateOverlayColor(newColor: Int) {
        if (_uiState.value.overlayColor == newColor) return
        _uiState.update { it.copy(overlayColor = newColor) }
        saveState(_uiState.value)
    }

    fun updateWindowPosition(x: Int, y: Int) {
        if (_uiState.value.windowX == x && _uiState.value.windowY == y) return
        _uiState.update { it.copy(windowX = x, windowY = y) }
        saveState(_uiState.value)
    }

    fun updateWindowSize(width: Int, height: Int) {
        if (_uiState.value.windowWidth == width && _uiState.value.windowHeight == height) return
        _uiState.update { it.copy(windowWidth = width, windowHeight = height) }
        saveState(_uiState.value)
    }

    fun setNested(nested: Boolean) {
        if (_uiState.value.isNested == nested) return
        _uiState.update {
            it.copy(
                isNested = nested,
                // Reset nested position when toggling off
                nestedX = if (nested) it.nestedX else -1,
                nestedY = if (nested) it.nestedY else -1
            )
        }
        saveState(_uiState.value)
    }

    fun updateNestedPosition(x: Int, y: Int) {
        if (_uiState.value.nestedX == x && _uiState.value.nestedY == y) return
        _uiState.update { it.copy(nestedX = x, nestedY = y) }
        saveState(_uiState.value)
    }

    fun setSoundsEnabled(enabled: Boolean) {
        if (_uiState.value.soundsEnabled == enabled) return
        _uiState.update { it.copy(soundsEnabled = enabled) }
        saveState(_uiState.value)
    }

    fun setSelectedSound(uri: String?) {
        if (_uiState.value.selectedSoundUri == uri) return
        _uiState.update { it.copy(selectedSoundUri = uri) }
        saveState(_uiState.value)
    }

    fun setMusicUrl(url: String?) {
        if (_uiState.value.musicUrl == url) return

        // Add to global recent URLs
        url?.let {
            musicUrlManager.addRecentUrl(it)
        }

        _uiState.update {
            it.copy(
                musicUrl = url,
                recentMusicUrls = musicUrlManager.getRecentUrls()
            )
        }
        saveState(_uiState.value)
    }

    // --- Preset Times Methods ---
    fun loadPresetFromManager(durationMillis: Long) {
        // When a preset is selected, update the timer duration
        setInitialDuration(durationMillis)
    }

    fun saveCurrentAsPreset() {
        val currentState = _uiState.value
        if (currentState.initialDurationMillis > 0) {
            val success = presetTimesManager.addPresetTime(
                currentState.initialDurationMillis,
                currentState.overlayColor
            )
            if (success) {
                // Reload preset times
                _uiState.update {
                    it.copy(presetTimes = presetTimesManager.getPresetTimes())
                }
                saveState(_uiState.value)
            }
        }
    }

    fun removePreset(presetId: String) {
        presetTimesManager.removePresetTime(presetId)
        // Reload preset times
        _uiState.update {
            it.copy(presetTimes = presetTimesManager.getPresetTimes())
        }
        saveState(_uiState.value)
    }

    fun refreshPresetTimes() {
        // Refresh preset times from manager (useful after dialog operations)
        _uiState.update {
            it.copy(presetTimes = presetTimesManager.getPresetTimes())
        }
        saveState(_uiState.value)
    }

    fun setShowPresetButton(show: Boolean) {
        if (_uiState.value.showPresetButton == show) return
        _uiState.update { it.copy(showPresetButton = show) }
        saveState(_uiState.value)
    }

    // --- Persistence ---
    private fun saveState(state: TimerState) {
        if (state.timerId <= 0) {
            Log.w(TAG, "Attempted to save state with invalid timerId: ${state.timerId}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                timerDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for timer ${state.timerId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for timer ${state.timerId}", e)
            }
        }
    }

    fun deleteState() {
        if (timerId <= 0) return
        stopTicker()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                timerDao.deleteById(timerId)
                Log.d(TAG, "Deleted state for timer $timerId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete state for timer $timerId", e)
            }
        }
    }

    // --- Mappers ---
    private fun mapEntityToState(entity: TimerStateEntity): TimerState {
        val recentUrlsList = try {
            val typeToken = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.recentMusicUrlsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recent URLs JSON, using empty list.", e)
            emptyList()
        }

        return TimerState(
            timerId = entity.timerId,
            uuid = try {
                UUID.fromString(entity.uuid)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid UUID in entity, generating new one", e)
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
            presetTimes = presetTimesList,
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
            presetTimesJson = presetTimesJson,
            showPresetButton = state.showPresetButton
        )
    }

    // --- Cleanup ---
    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for timerId: $timerId")
        stopTicker()
        super.onCleared()
    }
}