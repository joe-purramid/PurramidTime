// StopwatchViewModel.kt
package com.example.purramid.purramidtime.stopwatch.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.purramidtime.stopwatch.StopwatchRepository
import com.example.purramid.purramidtime.stopwatch.StopwatchService
import com.example.purramid.purramidtime.stopwatch.StopwatchState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StopwatchViewModel @Inject constructor(
    private val stopwatchRepository: StopwatchRepository,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        const val KEY_STOPWATCH_ID = "stopwatchId"
        private const val TAG = "StopwatchViewModel"
    }

    private var stopwatchId: Int = savedStateHandle.get<Int>(KEY_STOPWATCH_ID) ?: 0

    private val _uiState = MutableStateFlow(StopwatchState())
    val uiState: StateFlow<StopwatchState> = _uiState.asStateFlow()

    init {
        if (stopwatchId > 0) {
            loadStopwatchState()
        }
    }

    fun setStopwatchId(id: Int) {
        if (stopwatchId == 0 && id > 0) {
            stopwatchId = id
            savedStateHandle[KEY_STOPWATCH_ID] = id
            loadStopwatchState()
        }
    }

    private fun loadStopwatchState() {
        viewModelScope.launch {
            val state = stopwatchRepository.getStopwatchState(stopwatchId)
            _uiState.value = state
        }
    }

    // Settings update methods that communicate with the service
    fun setShowCentiseconds(show: Boolean) {
        if (_uiState.value.showCentiseconds == show) return

        _uiState.value = _uiState.value.copy(showCentiseconds = show)
        sendSettingUpdateToService("showCentiseconds", show)
    }

    fun updateOverlayColor(newColor: Int) {
        if (_uiState.value.overlayColor == newColor) return

        _uiState.value = _uiState.value.copy(overlayColor = newColor)
        sendSettingUpdateToService("overlayColor", newColor)
    }

    fun setSoundsEnabled(enabled: Boolean) {
        if (_uiState.value.soundsEnabled == enabled) return

        _uiState.value = _uiState.value.copy(soundsEnabled = enabled)
        sendSettingUpdateToService("soundsEnabled", enabled)
    }

    fun setShowLapTimes(show: Boolean) {
        if (_uiState.value.showLapTimes == show) return

        _uiState.value = _uiState.value.copy(showLapTimes = show)
        sendSettingUpdateToService("showLapTimes", show)
    }

    private fun sendSettingUpdateToService(key: String, value: Any) {
        val intent = Intent(context, StopwatchService::class.java).apply {
            action = StopwatchService.ACTION_UPDATE_SETTING
            putExtra(StopwatchService.EXTRA_STOPWATCH_ID, stopwatchId)
            putExtra(StopwatchService.EXTRA_SETTING_KEY, key)

            when (value) {
                is Boolean -> putExtra(StopwatchService.EXTRA_SETTING_VALUE, value)
                is Int -> putExtra(StopwatchService.EXTRA_SETTING_VALUE, value)
                is String -> putExtra(StopwatchService.EXTRA_SETTING_VALUE, value)
            }
        }
        context.startService(intent)
    }
}