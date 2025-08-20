// ClockStateManager.kt
package com.example.purramid.purramidtime.clock

import com.example.purramid.purramidtime.clock.repository.ClockRepository
import com.example.purramid.purramidtime.data.db.ClockStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

class ClockStateManager @Inject constructor(
    private val repository: ClockRepository,
    private val scope: CoroutineScope
) {
    private val _clockStates = MutableStateFlow<Map<Int, ClockRuntimeState>>(emptyMap())
    val clockStates: StateFlow<Map<Int, ClockRuntimeState>> = _clockStates

    data class ClockRuntimeState(
        val instanceId: Int,
        val isPaused: Boolean,
        val timeZoneId: ZoneId,
        val pausedAt: LocalTime? = null,
        val manuallySetTime: LocalTime? = null,
        val displaySeconds: Boolean = true,
        val is24Hour: Boolean = false,
        val clockColor: Int = android.graphics.Color.WHITE,
        val mode: String = "digital",
        val isNested: Boolean = false,
        val windowX: Int = 0,
        val windowY: Int = 0,
        val windowWidth: Int = -1,
        val windowHeight: Int = -1,
        val currentTime: LocalTime = LocalTime.now() // For display purposes
    )

    fun initializeClock(instanceId: Int, entity: ClockStateEntity) {
        val runtimeState = ClockRuntimeState(
            instanceId = instanceId,
            isPaused = entity.isPaused,
            timeZoneId = ZoneId.of(entity.timeZoneId),
            pausedAt = entity.manuallySetTimeSeconds?.let { LocalTime.ofSecondOfDay(it) },
            manuallySetTime = entity.manuallySetTimeSeconds?.let { LocalTime.ofSecondOfDay(it) },
            displaySeconds = entity.displaySeconds,
            is24Hour = entity.is24Hour,
            clockColor = entity.clockColor,
            mode = entity.mode,
            isNested = entity.isNested
        )
        _clockStates.value = _clockStates.value + (instanceId to runtimeState)
    }

    fun setManualTime(instanceId: Int, time: LocalTime) {
        val currentState = _clockStates.value[instanceId] ?: return
        val updatedState = currentState.copy(
            manuallySetTime = time,
            pausedAt = time,
            isPaused = true
        )
        
        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    fun getClockDisplayState(instanceId: Int): ClockRuntimeState? {
        val state = _clockStates.value[instanceId] ?: return null

        val currentTime = if (state.isPaused) {
            state.pausedAt ?: state.manuallySetTime ?: getCurrentTime(state.timeZoneId)
        } else {
            getCurrentTime(state.timeZoneId)
        }

        return state.copy(currentTime = currentTime)
    }
    fun getCurrentTimeForClock(instanceId: Int): LocalTime {
        val state = _clockStates.value[instanceId] ?: return LocalTime.now()

        return if (state.isPaused) {
            state.pausedAt ?: state.manuallySetTime ?: getCurrentTime(state.timeZoneId)
        } else {
            getCurrentTime(state.timeZoneId)
        }
    }

    // Make this public so it can be used if needed
    fun getCurrentTime(zoneId: ZoneId): LocalTime {
        return ZonedDateTime.now(zoneId).toLocalTime()
    }

    fun updateClockPaused(instanceId: Int, isPaused: Boolean) {
        val currentState = _clockStates.value[instanceId] ?: return
        val updatedState = if (isPaused) {
            currentState.copy(
                isPaused = true,
                pausedAt = getCurrentTime(currentState.timeZoneId)
            )
        } else {
            currentState.copy(isPaused = false)
        }

        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    fun updateClockSettings(
        instanceId: Int,
        mode: String? = null,
        color: Int? = null,
        is24Hour: Boolean? = null,
        timeZoneId: String? = null,
        displaySeconds: Boolean? = null,
        isNested: Boolean? = null
    ) {
        val currentState = _clockStates.value[instanceId] ?: return

        val updatedState = currentState.copy(
            mode = mode ?: currentState.mode,
            clockColor = color ?: currentState.clockColor,
            is24Hour = is24Hour ?: currentState.is24Hour,
            timeZoneId = timeZoneId?.let { ZoneId.of(it) } ?: currentState.timeZoneId,
            displaySeconds = displaySeconds ?: currentState.displaySeconds,
            isNested = isNested ?: currentState.isNested
        )

        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    fun updateWindowPosition(instanceId: Int, x: Int, y: Int) {
        val currentState = _clockStates.value[instanceId] ?: return
        val updatedState = currentState.copy(windowX = x, windowY = y)

        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    fun updateWindowSize(instanceId: Int, width: Int, height: Int) {
        val currentState = _clockStates.value[instanceId] ?: return
        val updatedState = currentState.copy(windowWidth = width, windowHeight = height)

        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    fun resetTime(instanceId: Int) {
        val currentState = _clockStates.value[instanceId] ?: return
        val updatedState = currentState.copy(
            isPaused = false,
            pausedAt = null,
            manuallySetTime = null
        )

        _clockStates.value = _clockStates.value + (instanceId to updatedState)
        persistState(instanceId, updatedState)
    }

    private fun persistState(instanceId: Int, state: ClockRuntimeState) {
        scope.launch {
            val entity = ClockStateEntity(
                instanceId = instanceId,
                timeZoneId = state.timeZoneId.id,
                isPaused = state.isPaused,
                displaySeconds = state.displaySeconds,
                is24Hour = state.is24Hour,
                clockColor = state.clockColor,
                mode = state.mode,
                isNested = state.isNested,
                manuallySetTimeSeconds = state.manuallySetTime?.toSecondOfDay()?.toLong()
            )
            repository.saveClockState(entity)
        }
    }

    fun removeClock(instanceId: Int) {
        _clockStates.value = _clockStates.value - instanceId
        scope.launch {
            repository.deleteClockState(instanceId)
        }
    }
}