// ClockDao.kt
package com.example.purramid.purramidtime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: ClockStateEntity)

    @Query("SELECT * FROM clock_state WHERE instanceId = :instanceId")
    fun getByInstanceIdFlow(instanceId: Int): Flow<ClockStateEntity?>

    @Query("SELECT * FROM clock_state WHERE instanceId = :instanceId")
    suspend fun getByInstanceId(instanceId: Int): ClockStateEntity?

    @Query("SELECT * FROM clock_state")
    suspend fun getAllStates(): List<ClockStateEntity> // To load all clocks on service start

    @Query("SELECT COUNT(*) FROM clock_state")
    suspend fun getActiveInstanceCount(): Int

    @Query("DELETE FROM clock_state WHERE instanceId = :instanceId")
    suspend fun deleteByInstanceId(instanceId: Int)

    @Query("DELETE FROM clock_state")
    suspend fun clearAll() // Optional: For debugging or resetting
}