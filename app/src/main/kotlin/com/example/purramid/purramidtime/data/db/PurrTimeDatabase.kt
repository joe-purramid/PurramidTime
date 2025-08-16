// PurrTimeDatabase.kt
package com.example.purramid.purramidtime.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import java.util.UUID


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
    exportSchema = false // Set to true if you want to export the schema to a file for version control (recommended for production apps)
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
        @Volatile
        private var INSTANCE: PurrTimeDatabase? = null

        // Migration from 0 to 1: Add UUID to screen_mask_state
        private val MIGRATION_0_1 = object : Migration(0, 1) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screen_mask_state ADD COLUMN uuid TEXT NOT NULL DEFAULT '${UUID.randomUUID()}'")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN isNested INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN nestedX INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN nestedY INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN soundsEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN selectedSoundUri TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN musicUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN recentMusicUrlsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE timer_state ADD COLUMN showLapTimes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clock_state RENAME COLUMN clockId TO instanceId")
                db.execSQL("ALTER TABLE clock_alarms RENAME COLUMN clockId TO instanceId")
                db.execSQL("ALTER TABLE randomizer_instances ADD COLUMN windowState TEXT NOT NULL DEFAULT 'normal'")
            }
        }

        // Future migrations would be added here

        fun getDatabase(context: Context): PurrTimeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PurrTimeDatabase::class.java,
                    "purramid_database"
                )
                    .addMigrations(
                        MIGRATION_0_1,
                        // Add future migrations here
                    )
                    .fallbackToDestructiveMigrationOnDowngrade(false) // Only destroy on downgrade
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}