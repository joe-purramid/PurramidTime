// StopwatchDao.kt
package com.example.purramid.purramidtime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StopwatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: StopwatchStateEntity)

    @Query("SELECT * FROM stopwatch_state WHERE stopwatchId = :id")
    suspend fun getById(id: Int): StopwatchStateEntity?

    @Query("SELECT * FROM stopwatch_state")
    suspend fun getAllStates(): List<StopwatchStateEntity>

    @Query("DELETE FROM stopwatch_state WHERE stopwatchId = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM stopwatch_state")
    suspend fun clearAll()

    // Standardized methods for instance management
    @Query("SELECT COUNT(*) FROM stopwatch_state")
    suspend fun getActiveInstanceCount(): Int

    @Query("SELECT * FROM stopwatch_state WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): StopwatchStateEntity?

    // Additional standardized methods matching other app-intents
    @Query("SELECT stopwatchId FROM stopwatch_state")
    suspend fun getAllInstanceIds(): List<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM stopwatch_state WHERE stopwatchId = :id)")
    suspend fun instanceExists(id: Int): Boolean
}