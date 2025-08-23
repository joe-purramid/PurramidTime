// TimerViewModel.kt
package com.example.purramid.purramidtime.timer.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.purramid.purramidtime.timer.MusicUrlManager
import com.example.purramid.purramidtime.timer.PresetTimesManager
import com.example.purramid.purramidtime.timer.TimerState
import com.example.purramid.purramidtime.timer.repository.TimerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val timerRepository: TimerRepository,
    private val savedStateHandle: SavedStateHandle,
    private val musicUrlManager: MusicUrlManager,
    private val presetTimesManager: PresetTimesManager
) : ViewModel() {

    companion object {
        const val KEY_TIMER_ID = "timerId"
        private const val TAG = "TimerViewModel"
    }

    // Get timerId from savedStateHandle or arguments
    private val timerId: Int = savedStateHandle.get<Int>(KEY_TIMER_ID) ?: 0

    // Directly expose the repository's state flow for this timer
    val uiState: StateFlow<TimerState> = if (timerId > 0) {
        timerRepository.getTimerStateFlow(timerId)
    } else {
        MutableStateFlow(TimerState()).asStateFlow()
    }

    init {
        Log.d(TAG, "Initializing ViewModel for timerId: $timerId")
        if (timerId > 0) {
            // Initialize timer in repository
            viewModelScope.launch {
                timerRepository.initializeTimer(timerId)
            }
        }
    }

    // --- Timer Controls (delegate to repository) ---

    fun togglePlayPause() {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.togglePlayPause(timerId)
        }
    }

    fun resetTimer() {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.resetTimer(timerId)
        }
    }

    fun setInitialDuration(durationMillis: Long) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.setInitialDuration(timerId, durationMillis)
        }
    }

    fun setPlaySoundOnEnd(play: Boolean) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.setPlaySoundOnEnd(timerId, play)
        }
    }

    fun updateOverlayColor(newColor: Int) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.updateOverlayColor(timerId, newColor)
        }
    }

    fun setNested(nested: Boolean) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.setNested(timerId, nested)
        }
    }

    fun setSelectedSound(uri: String?) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.setSelectedSound(timerId, uri)
        }
    }

    fun setMusicUrl(url: String?) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.setMusicUrl(timerId, url)
        }
    }

    fun loadPresetFromManager(durationMillis: Long) {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.loadPresetDuration(timerId, durationMillis)
        }
    }

    fun refreshPresetTimes() {
        if (timerId <= 0) return
        viewModelScope.launch {
            timerRepository.refreshPresetTimes(timerId)
        }
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared for timerId: $timerId")
        super.onCleared()
    }
}