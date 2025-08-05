// TimerCoordinator.kt
package com.example.purramid.thepurramid.timers

import android.content.Context
import com.example.purramid.thepurramid.data.db.TimerDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates nested timer positioning across multiple timer instances
 */
@Singleton
class TimerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timerDao: TimerDao
) {
    
    /**
     * Get the count of nested timers that should be stacked above this timer
     * @param currentTimerId The ID of the current timer
     * @return The number of nested timers above this one
     */
    suspend fun getNestedTimerStackPosition(currentTimerId: Int): Int = withContext(Dispatchers.IO) {
        try {
            val allStates = timerDao.getAllStates()
            
            // Filter to only nested timers that aren't the current timer
            val nestedTimers = allStates.filter { 
                it.isNested && it.timerId != currentTimerId 
            }
            
            // Sort by timer ID to ensure consistent stacking order
            val sortedNestedTimers = nestedTimers.sortedBy { it.timerId }
            
            // Find the position of current timer in the stack
            val currentTimerIndex = sortedNestedTimers.indexOfFirst { it.timerId == currentTimerId }
            
            // If not found, this timer will be at the bottom of the stack
            if (currentTimerIndex == -1) {
                return@withContext sortedNestedTimers.size
            }
            
            return@withContext currentTimerIndex
        } catch (e: Exception) {
            0 // Default to top position on error
        }
    }
    
    /**
     * Get all nested timer positions for conflict resolution
     * @return Map of timer ID to Y position
     */
    suspend fun getNestedTimerPositions(): Map<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val allStates = timerDao.getAllStates()
            
            allStates
                .filter { it.isNested }
                .associate { it.timerId to it.nestedY }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Synchronous version for use in UI thread (use sparingly)
     */
    fun getNestedTimerStackPositionSync(currentTimerId: Int): Int {
        return runBlocking {
            getNestedTimerStackPosition(currentTimerId)
        }
    }
}