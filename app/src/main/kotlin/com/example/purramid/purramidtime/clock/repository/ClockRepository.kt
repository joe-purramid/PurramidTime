// ClockRepository.kt
package com.example.purramid.purramidtime.clock.repository

import com.example.purramid.purramidtime.data.db.ClockDao
import com.example.purramid.purramidtime.data.db.ClockStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClockRepository @Inject constructor(
    private val clockDao: ClockDao
) {
    // In-memory state for active clocks
    private val _activeClockStates = MutableStateFlow<Map<Int, ClockStateEntity>>(emptyMap())
    val activeClockStates: StateFlow<Map<Int, ClockStateEntity>> = _activeClockStates

    suspend fun loadAllClockStates(): List<ClockStateEntity> {
        return clockDao.getAllStates()
    }

    suspend fun saveClockState(state: ClockStateEntity) {
        clockDao.insertOrUpdate(state)
        updateActiveState(state.instanceId, state)
    }

    suspend fun deleteClockState(instanceId: Int) {
        clockDao.deleteByInstanceId(instanceId)
        removeActiveState(instanceId)
    }

    fun getClockState(instanceId: Int): ClockStateEntity? {
        return _activeClockStates.value[instanceId]
    }

    fun observeClockState(instanceId: Int): Flow<ClockStateEntity?> {
        return clockDao.getByInstanceIdFlow(instanceId)
    }

    private fun updateActiveState(instanceId: Int, state: ClockStateEntity) {
        _activeClockStates.value = _activeClockStates.value + (instanceId to state)
    }

    private fun removeActiveState(instanceId: Int) {
        _activeClockStates.value = _activeClockStates.value - instanceId
    }
}