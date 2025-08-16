// StopwatchViewModel.kt
package com.example.purramid.purramidtime.stopwatch.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.purramidtime.data.db.StopwatchDao
import com.example.purramid.purramidtime.data.db.StopwatchStateEntity
import com.example.purramid.purramidtime.stopwatch.StopwatchState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val stopwatchDao: StopwatchDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_STOPWATCH_ID = "stopwatchId"
        private const val TAG = "StopwatchViewModel"
        private const val TICK_INTERVAL_MS = 50L
        private const val MAX_LAPS = 10 // As per specification
    }

    // Initialize stopwatchId - will be set by setStopwatchId() from Service
    private var stopwatchId: Int = 0

    private val _uiState = MutableStateFlow(StopwatchState(stopwatchId = stopwatchId))
    val uiState: StateFlow<StopwatchState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private val gson = Gson()

    init {
        Log.d(TAG, "Initializing ViewModel")
        // StopwatchId will be set by the Service
    }

    fun setStopwatchId(id: Int) {
        if (stopwatchId == 0 && id > 0) {
            stopwatchId = id
            savedStateHandle[KEY_STOPWATCH_ID] = id
            loadInitialState(id)
        }
    }

    private fun loadInitialState(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = stopwatchDao.getById(id)
                withContext(Dispatchers.Main) {
                    if (entity != null) {
                        Log.d(TAG, "Loaded state from DB for stopwatch $id")
                        _uiState.value = mapEntityToState(entity)
                        if (_uiState.value.isRunning) {
                            startTicker()
                        }
                    } else {
                        Log.d(TAG, "No saved state for stopwatch $id, using defaults.")
                        val defaultState = StopwatchState(
                            stopwatchId = id,
                            uuid = UUID.randomUUID()
                        )
                        _uiState.value = defaultState
                        saveState(defaultState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state for stopwatch $id", e)
                withContext(Dispatchers.Main) {
                    val defaultState = StopwatchState(
                        stopwatchId = id,
                        uuid = UUID.randomUUID()
                    )
                    _uiState.value = defaultState
                }
            }
        }
    }

    // --- Stopwatch Controls ---

    fun togglePlayPause() {
        val currentState = _uiState.value
        val newRunningState = !currentState.isRunning
        _uiState.update { it.copy(isRunning = newRunningState) }

        if (newRunningState) {
            startTicker()
        } else {
            stopTicker()
        }
        saveState(_uiState.value)
    }

    fun resetStopwatch() {
        stopTicker()
        _uiState.update {
            it.copy(
                isRunning = false,
                currentMillis = 0L,
                laps = emptyList()
            )
        }
        saveState(_uiState.value)
    }

    fun addLap() {
        val currentState = _uiState.value
        if (!currentState.isRunning) return

        // Check max laps limit from specification
        if (currentState.laps.size >= MAX_LAPS) {
            Log.d(TAG, "Maximum number of laps ($MAX_LAPS) reached")
            return
        }

        val currentLaps = currentState.laps.toMutableList()
        currentLaps.add(currentState.currentMillis)
        _uiState.update { it.copy(laps = currentLaps) }
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
                val newMillis = initialMillis + elapsed

                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(currentMillis = newMillis) }
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

    // --- Settings Updates ---
    fun setShowCentiseconds(show: Boolean) {
        if (_uiState.value.showCentiseconds == show) return
        _uiState.update { it.copy(showCentiseconds = show) }
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

    fun setShowLapTimes(show: Boolean) {
        if (_uiState.value.showLapTimes == show) return
        _uiState.update { it.copy(showLapTimes = show) }
        saveState(_uiState.value)
    }

    fun setSelectedSound(uri: String?) {
        if (_uiState.value.selectedSoundUri == uri) return
        _uiState.update { it.copy(selectedSoundUri = uri) }
        saveState(_uiState.value)
    }

    fun setMusicUrl(url: String?) {
        if (_uiState.value.musicUrl == url) return

        // Update recent URLs list
        val recentUrls = _uiState.value.recentMusicUrls.toMutableList()
        url?.let {
            recentUrls.remove(it) // Remove if already exists
            recentUrls.add(0, it) // Add to beginning
            if (recentUrls.size > 3) {
                recentUrls.removeAt(3) // Keep only last 3
            }
        }

        _uiState.update {
            it.copy(
                musicUrl = url,
                recentMusicUrls = recentUrls
            )
        }
        saveState(_uiState.value)
    }

    // --- Persistence ---
    private fun saveState(state: StopwatchState) {
        if (state.stopwatchId <= 0) {
            Log.w(TAG, "Attempted to save state with invalid stopwatchId: ${state.stopwatchId}")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = mapStateToEntity(state)
                stopwatchDao.insertOrUpdate(entity)
                Log.d(TAG, "Saved state for stopwatch ${state.stopwatchId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save state for stopwatch ${state.stopwatchId}", e)
            }
        }
    }

    fun deleteState() {
        if (stopwatchId <= 0) return
        stopTicker()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stopwatchDao.deleteById(stopwatchId)
                Log.d(TAG, "Deleted state for stopwatch $stopwatchId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete state for stopwatch $stopwatchId", e)
            }
        }
    }

    // --- Mappers ---
    private fun mapEntityToState(entity: StopwatchStateEntity): StopwatchState {
        val lapsList = try {
            val typeToken = object : TypeToken<List<Long>>() {}.type
            gson.fromJson<List<Long>>(entity.lapsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse laps JSON, using empty list.", e)
            emptyList()
        }

        val recentUrlsList = try {
            val typeToken = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.recentMusicUrlsJson, typeToken) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recent URLs JSON, using empty list.", e)
            emptyList()
        }

        return StopwatchState(
            stopwatchId = entity.stopwatchId,
            uuid = try {
                UUID.fromString(entity.uuid)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid UUID in entity, generating new one", e)
                UUID.randomUUID()
            },
            currentMillis = entity.currentMillis,
            isRunning = entity.isRunning,
            laps = lapsList,
            showCentiseconds = entity.showCentiseconds,
            overlayColor = entity.overlayColor,
            windowX = entity.windowX,
            windowY = entity.windowY,
            windowWidth = entity.windowWidth,
            windowHeight = entity.windowHeight,
            isNested = entity.isNested,
            nestedX = entity.nestedX,
            nestedY = entity.nestedY,
            soundsEnabled = entity.soundsEnabled,
            selectedSoundUri = entity.selectedSoundUri,
            musicUrl = entity.musicUrl,
            recentMusicUrls = recentUrlsList,
            showLapTimes = entity.showLapTimes
        )
    }

    private fun mapStateToEntity(state: StopwatchState): StopwatchStateEntity {
        val lapsJson = gson.toJson(state.laps)
        val recentUrlsJson = gson.toJson(state.recentMusicUrls)

        return StopwatchStateEntity(
            stopwatchId = state.stopwatchId,
            uuid = state.uuid.toString(),
            currentMillis = state.currentMillis,
            isRunning = state.isRunning,
            lapsJson = lapsJson,
            showCentiseconds = state.showCentiseconds,
            overlayColor = state.overlayColor,
            windowX = state.windowX,
            windowY = state.windowY,
            windowWidth = state.windowWidth,
            windowHeight = state.windowHeight,
            isNested = state.isNested,
            nestedX = state.nestedX,
            nestedY = state.nestedY,
            soundsEnabled = state.soundsEnabled,
            selectedSoundUri = state.selectedSoundUri,
            musicUrl = state.musicUrl,
            recentMusicUrlsJson = recentUrlsJson,
            showLapTimes = state.showLapTimes
        )
    }

    // --- Cleanup ---
    fun shouldCleanupOnClose(): Boolean {
        // This could be called by the service to determine cleanup behavior
        return stopwatchDao.getActiveInstanceCount() > 1
    }

    fun cleanupIfNotLast() {
        viewModelScope.launch(Dispatchers.IO) {
            val activeCount = stopwatchDao.getActiveInstanceCount()
            if (activeCount > 1) {
                // Not the last instance, clean up
                deleteState()
            } else {
                // Last instance, just save current state
                saveState(_uiState.value)
            }
        }
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for stopwatchId: $stopwatchId")
        stopTicker()
        super.onCleared()
    }
}