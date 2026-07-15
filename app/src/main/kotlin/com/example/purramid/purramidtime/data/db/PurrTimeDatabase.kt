// PurrTimeDatabase.kt
package com.example.purramid.purramidtime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters


/**
 * The Room database for Purramid Time Tools application.
 * This contains tables for Clock, Stopwatch, and Timer features.
 */
@Database(
    entities = [
        // List all your entity classes here
        CityEntity::class,
        ClockAlarmEntity::class,
        ClockStateEntity::class,
        StopwatchStateEntity::class,
        TimerStateEntity::class,
        TimeZoneBoundaryEntity::class,
    ],
    version = 1, // Release to production
    exportSchema = true // Schemas are written to app/schemas (room.schemaLocation) and are the diff future migrations validate against.
)
@TypeConverters(Converters::class) // Register the TypeConverters class
abstract class PurrTimeDatabase : RoomDatabase() {

    /**
     * Abstract function to get the Data Access Objects
     * Room will generate the implementation.
     */
    abstract fun cityDao(): CityDao
    abstract fun clockAlarmDao(): ClockAlarmDao
    abstract fun clockDao(): ClockDao
    abstract fun stopwatchDao(): StopwatchDao
    abstract fun timerDao(): TimerDao
    abstract fun timeZoneDao(): TimeZoneDao

    companion object {
        private const val DATABASE_NAME = "purramid_time_db"

        @Volatile
        private var INSTANCE: PurrTimeDatabase? = null

        // No migrations yet: the schema is at version 1. Add Migration objects here
        // (and bump the @Database version) when the schema changes; app/schemas holds
        // the exported JSON to validate them against.

        fun getDatabase(context: Context): PurrTimeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PurrTimeDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigrationOnDowngrade(false) // Only destroy on downgrade
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}