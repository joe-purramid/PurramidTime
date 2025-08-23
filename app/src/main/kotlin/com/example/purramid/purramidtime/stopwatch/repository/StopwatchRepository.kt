// StopwatchRepository.kt
package com.example.purramid.purramidtime.stopwatch

import android.util.Log
import com.example.purramid.purramidtime.data.db.StopwatchDao
import com.example.purramid.purramidtime.data.db.StopwatchStateEntity
import com.example.purramid.purramidtime.di.IoDispatcher
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StopwatchRepository @Inject constructor(
    private val stopwatchDao: StopwatchDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "StopwatchRepository"
    }

    private val gson = Gson()
    
    // Cache of active stopwatch states
    private val activeStates = mutableMapOf<Int, MutableStateFlow<StopwatchState>>()

    suspend fun getStopwatchState(stopwatchId: Int): StopwatchState = withContext(ioDispatcher) {
        val entity = stopwatchDao.getById(stopwatchId)
        if (entity != null) {
            mapEntityToState(entity)
        } else {
            val defaultState = StopwatchState(
                stopwatchId = stopwatchId,
                uuid = UUID.randomUUID()
            )
            saveStopwatchState(defaultState)
            defaultState
        }
    }

    fun getStopwatchStateFlow(stopwatchId: Int): StateFlow<StopwatchState> {
        return activeStates.getOrPut(stopwatchId) {
            MutableStateFlow(StopwatchState(stopwatchId = stopwatchId))
        }.asStateFlow()
    }

    suspend fun updateStopwatchState(state: StopwatchState) {
        // Update cache
        activeStates.getOrPut(state.stopwatchId) {
            MutableStateFlow(state)
        }.value = state
        
        // Persist to database
        saveStopwatchState(state)
    }

    suspend fun saveStopwatchState(state: StopwatchState) = withContext(ioDispatcher) {
        try {
            val entity = mapStateToEntity(state)
            stopwatchDao.insertOrUpdate(entity)
            Log.d(TAG, "Saved state for stopwatch ${state.stopwatchId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state for stopwatch ${state.stopwatchId}", e)
        }
    }

    suspend fun deleteStopwatch(stopwatchId: Int) = withContext(ioDispatcher) {
        try {
            stopwatchDao.deleteById(stopwatchId)
            activeStates.remove(stopwatchId)
            Log.d(TAG, "Deleted stopwatch $stopwatchId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete stopwatch $stopwatchId", e)
        }
    }

    suspend fun getActiveInstanceCount(): Int = withContext(ioDispatcher) {
        stopwatchDao.getActiveInstanceCount()
    }

    suspend fun getAllInstanceIds(): List<Int> = withContext(ioDispatcher) {
        stopwatchDao.getAllInstanceIds()
    }

    suspend fun cleanupOrphanedInstances(activeInstanceIds: Set<Int>) = withContext(ioDispatcher) {
        val allDbInstances = stopwatchDao.getAllInstanceIds()
        val orphanedInstances = allDbInstances.filter { it !in activeInstanceIds && it != 1 }
        
        orphanedInstances.forEach { instanceId ->
            Log.d(TAG, "Cleaning up orphaned instance: $instanceId")
            stopwatchDao.deleteById(instanceId)
        }
    }

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
            soundsEnabled = state.soundsEnabled,
            selectedSoundUri = state.selectedSoundUri,
            musicUrl = state.musicUrl,
            recentMusicUrlsJson = recentUrlsJson,
            showLapTimes = state.showLapTimes
        )
    }

    fun clearCache(stopwatchId: Int) {
        activeStates.remove(stopwatchId)
    }
}