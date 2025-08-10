// StopwatchCoordinator.kt
package com.example.purramid.purramidtime.stopwatch

import android.content.Context
import com.example.purramid.purramidtime.data.db.StopwatchDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates nested stopwatch positioning across multiple stopwatch instances
 */
@Singleton
class StopwatchCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stopwatchDao: StopwatchDao
) {
    
    /**
     * Get the count of nested stopwatches that should be stacked above this stopwatch
     * @param currentStopwatchId The ID of the current stopwatch
     * @return The number of nested stopwatches above this one
     */
    suspend fun getNestedStopwatchStackPosition(currentStopwatchId: Int): Int = withContext(Dispatchers.IO) {
        try {
            val allStates = stopwatchDao.getAllStates()
            
            // Filter to only nested stopwatches that aren't the current stopwatch
            val nestedStopwatches = allStates.filter { 
                it.isNested && it.stopwatchId != currentStopwatchId 
            }
            
            // Sort by stopwatch ID to ensure consistent stacking order
            val sortedNestedStopwatches = nestedStopwatches.sortedBy { it.stopwatchId }
            
            // Find the position of current stopwatch in the stack
            val currentStopwatchIndex = sortedNestedStopwatches.indexOfFirst { it.stopwatchId == currentStopwatchId }
            
            // If not found, this stopwatch will be at the bottom of the stack
            if (currentStopwatchIndex == -1) {
                return@withContext sortedNestedStopwatches.size
            }
            
            return@withContext currentStopwatchIndex
        } catch (e: Exception) {
            0 // Default to top position on error
        }
    }
    
    /**
     * Get all nested stopwatch positions for conflict resolution
     * @return Map of stopwatch ID to Y position
     */
    suspend fun getNestedStopwatchPositions(): Map<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val allStates = stopwatchDao.getAllStates()
            
            allStates
                .filter { it.isNested }
                .associate { it.stopwatchId to it.nestedY }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Synchronous version for use in UI thread (use sparingly)
     */
    fun getNestedStopwatchStackPositionSync(currentStopwatchId: Int): Int {
        return runBlocking {
            getNestedStopwatchStackPosition(currentStopwatchId)
        }
    }
}