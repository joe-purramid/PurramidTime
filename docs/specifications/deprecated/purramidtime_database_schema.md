# Purramid Time - Database Schema
This database is shared among Clock, Stopwatch, and Timer app-intents.

## Database Overview

**Database Name**: `purramid_time_db`  
**ORM Framework**: Room Database  
**Database Version**: 1  
**Migration Strategy**: Hybrid - Destructive in DEBUG builds, proper migrations in RELEASE builds

## Entity Definitions

### **ClockState Entity**
```kotlin
@Entity(tableName = "clock_state")
data class ClockStateEntity(
    @PrimaryKey
    val instanceId: Int,
    
    @ColumnInfo(name = "uuid")
    val uuid: UUID = UUID.randomUUID(),
    
    @ColumnInfo(name = "timezone_id")
    val timeZoneId: String = ZoneId.systemDefault().id,
    
    @ColumnInfo(name = "is_paused")
    val isPaused: Boolean = false,
    
    @ColumnInfo(name = "display_seconds")
    val displaySeconds: Boolean = true,
    
    @ColumnInfo(name = "is_24_hour")
    val is24Hour: Boolean = false,
    
    @ColumnInfo(name = "clock_color")
    val clockColor: Int = android.graphics.Color.WHITE,
    
    @ColumnInfo(name = "mode")
    val mode: String = "digital", // "digital" or "analog"
    
    @ColumnInfo(name = "is_nested")
    val isNested: Boolean = false,
    
    // Window position and size
    @ColumnInfo(name = "window_x")
    val windowX: Int = 0,
    
    @ColumnInfo(name = "window_y")
    val windowY: Int = 0,
    
    @ColumnInfo(name = "window_width")
    val windowWidth: Int = -1, // -1 = WRAP_CONTENT
    
    @ColumnInfo(name = "window_height")
    val windowHeight: Int = -1, // -1 = WRAP_CONTENT
    
    @ColumnInfo(name = "manually_set_time_seconds")
    val manuallySetTimeSeconds: Long? = null // Nullable for auto time
)
```

### **ClockAlarm Entity**
```kotlin
@Entity(
    tableName = "clock_alarms",
    foreignKeys = [
        ForeignKey(
            entity = ClockStateEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["instanceId"]),
        Index(value = ["time"]),
        Index(value = ["isEnabled"])
    ]
)
data class ClockAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val alarmId: Long = 0,
    
    @ColumnInfo(name = "instance_id")
    val instanceId: Int, // Associated clock instance
    
    @ColumnInfo(name = "uuid")
    val uuid: UUID = UUID.randomUUID(),
    
    @ColumnInfo(name = "time")
    val time: LocalTime, // Alarm time
    
    @ColumnInfo(name = "timezone_id")
    val timeZoneId: String? = null, // null = system default
    
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    
    @ColumnInfo(name = "label")
    val label: String = "",
    
    @ColumnInfo(name = "days_of_week")
    val daysOfWeek: Int = 0, // Bit flags: 0=one-time, 1-127=repeat
    
    @ColumnInfo(name = "sound_enabled")
    val soundEnabled: Boolean = true,
    
    @ColumnInfo(name = "vibration_enabled")
    val vibrationEnabled: Boolean = true
)

// Days of week bit flags
object AlarmDays {
    const val MONDAY = 1
    const val TUESDAY = 2
    const val WEDNESDAY = 4
    const val THURSDAY = 8
    const val FRIDAY = 16
    const val SATURDAY = 32
    const val SUNDAY = 64
}
```

### **TimerState Entity**
```kotlin
@Entity(tableName = "timer_state")
data class TimerStateEntity(
    @PrimaryKey
    val timerId: Int,
    
    @ColumnInfo(name = "uuid")
    val uuid: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "initial_duration_millis")
    val initialDurationMillis: Long = 0L, // Set duration for countdown
    
    @ColumnInfo(name = "current_millis")
    val currentMillis: Long = 0L,
    
    @ColumnInfo(name = "is_running")
    val isRunning: Boolean = false,
    
    @ColumnInfo(name = "play_sound_on_end")
    val playSoundOnEnd: Boolean = true, // Alert when countdown reaches zero
    
    @ColumnInfo(name = "overlay_color")
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    
    // Window position
    @ColumnInfo(name = "window_x")
    val windowX: Int = 0,
    
    @ColumnInfo(name = "window_y")
    val windowY: Int = 0,
    
    @ColumnInfo(name = "window_width")
    val windowWidth: Int = -1,
    
    @ColumnInfo(name = "window_height")
    val windowHeight: Int = -1,
    
    // Nested timer fields
    @ColumnInfo(name = "is_nested")
    val isNested: Boolean = false,
    
    @ColumnInfo(name = "nested_x")
    val nestedX: Int = -1,
    
    @ColumnInfo(name = "nested_y")
    val nestedY: Int = -1,
    
    // Sound fields
    @ColumnInfo(name = "sounds_enabled")
    val soundsEnabled: Boolean = true,
    
    @ColumnInfo(name = "selected_sound_uri")
    val selectedSoundUri: String? = null,
    
    @ColumnInfo(name = "music_url")
    val musicUrl: String? = null,
    
    @ColumnInfo(name = "recent_music_urls_json")
    val recentMusicUrlsJson: String = "[]"
)

### **StopwatchState Entity**
```kotlin
@Entity(tableName = "stopwatch_state")
data class StopwatchStateEntity(
    @PrimaryKey
    val stopwatchId: Int,
    
    @ColumnInfo(name = "uuid")
    val uuid: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "current_millis")
    val currentMillis: Long = 0L,
    
    @ColumnInfo(name = "is_running")
    val isRunning: Boolean = false,
    
    @ColumnInfo(name = "laps_json")
    val lapsJson: String = "[]", // JSON array of lap times
    
    @ColumnInfo(name = "show_centiseconds")
    val showCentiseconds: Boolean = true,
    
    @ColumnInfo(name = "overlay_color")
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    
    // Window position
    @ColumnInfo(name = "window_x")
    val windowX: Int = 0,
    
    @ColumnInfo(name = "window_y")
    val windowY: Int = 0,
    
    @ColumnInfo(name = "window_width")
    val windowWidth: Int = -1,
    
    @ColumnInfo(name = "window_height")
    val windowHeight: Int = -1,
    
    // Sound fields for lap notifications
    @ColumnInfo(name = "sounds_enabled")
    val soundsEnabled: Boolean = false,
    
    @ColumnInfo(name = "selected_sound_uri")
    val selectedSoundUri: String? = null,
    
    @ColumnInfo(name = "music_url")
    val musicUrl: String? = null,
    
    @ColumnInfo(name = "recent_music_urls_json")
    val recentMusicUrlsJson: String = "[]",
    
    @ColumnInfo(name = "show_lap_times")
    val showLapTimes: Boolean = false
)

```

### **City Entity**
```kotlin
@Entity(
    tableName = "cities",
    indices = [
        Index(value = ["timezone_id"], unique = false)
    ]
)
data class CityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    @ColumnInfo(name = "city_name")
    val name: String,
    
    @ColumnInfo(name = "country_name")
    val country: String,
    
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    
    @ColumnInfo(name = "timezone_id")
    val timezone: String // IANA Time Zone ID
)
```

### **TimeZoneBoundary Entity**
```kotlin
@Entity(tableName = "time_zone_boundaries")
data class TimeZoneBoundaryEntity(
    @PrimaryKey
    val tzid: String, // IANA timezone identifier
    
    @ColumnInfo(name = "polygon_wkt")
    val polygonWkt: String // WKT format polygon data
)
```

## DAO Interfaces

### **Clock DAO**
```kotlin
@Dao
interface ClockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: ClockStateEntity)
    
    @Query("SELECT * FROM clock_state WHERE instanceId = :instanceId")
    suspend fun getByInstanceId(instanceId: Int): ClockStateEntity?
    
    @Query("SELECT * FROM clock_state")
    suspend fun getAllStates(): List<ClockStateEntity>
    
    @Query("SELECT COUNT(*) FROM clock_state")
    suspend fun getActiveInstanceCount(): Int
    
    @Query("DELETE FROM clock_state WHERE instanceId = :instanceId")
    suspend fun deleteByInstanceId(instanceId: Int)
    
    @Query("DELETE FROM clock_state")
    suspend fun clearAll()
}
```

### **ClockAlarm DAO**
```kotlin
@Dao
interface ClockAlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: ClockAlarmEntity): Long
    
    @Update
    suspend fun updateAlarm(alarm: ClockAlarmEntity)
    
    @Query("SELECT * FROM clock_alarms WHERE instanceId = :instanceId ORDER BY time ASC")
    fun getAlarmsForClock(instanceId: Int): Flow<List<ClockAlarmEntity>>
    
    @Query("SELECT * FROM clock_alarms WHERE isEnabled = 1 ORDER BY time ASC")
    fun getAllActiveAlarms(): Flow<List<ClockAlarmEntity>>
    
    @Query("SELECT * FROM clock_alarms WHERE alarmId = :alarmId")
    suspend fun getAlarmById(alarmId: Long): ClockAlarmEntity?
    
    @Query("DELETE FROM clock_alarms WHERE alarmId = :alarmId")
    suspend fun deleteAlarm(alarmId: Long)
    
    @Query("DELETE FROM clock_alarms WHERE instanceId = :instanceId")
    suspend fun deleteAlarmsForClock(instanceId: Int)
    
    @Query("UPDATE clock_alarms SET isEnabled = :enabled WHERE alarmId = :alarmId")
    suspend fun setAlarmEnabled(alarmId: Long, enabled: Boolean)
}
```

### **Timer DAO**
```kotlin
@Dao
interface TimerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: TimerStateEntity)
    
    @Query("SELECT * FROM timer_state WHERE timerId = :id")
    suspend fun getById(id: Int): TimerStateEntity?
    
    @Query("SELECT * FROM timer_state")
    suspend fun getAllStates(): List<TimerStateEntity>
    
    @Query("DELETE FROM timer_state WHERE timerId = :id")
    suspend fun deleteById(id: Int)
    
    @Query("DELETE FROM timer_state")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM timer_state")
    suspend fun getActiveInstanceCount(): Int
    
    @Query("SELECT * FROM timer_state WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): TimerStateEntity?
    
    @Query("SELECT timerId FROM timer_state")
    suspend fun getAllInstanceIds(): List<Int>
    
    @Query("SELECT EXISTS(SELECT 1 FROM timer_state WHERE timerId = :id)")
    suspend fun instanceExists(id: Int): Boolean
}
```

### **Stopwatch DAO**
```kotlin
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
    
    @Query("SELECT COUNT(*) FROM stopwatch_state")
    suspend fun getActiveInstanceCount(): Int
    
    @Query("SELECT * FROM stopwatch_state WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): StopwatchStateEntity?
    
    @Query("SELECT stopwatchId FROM stopwatch_state")
    suspend fun getAllInstanceIds(): List<Int>
    
    @Query("SELECT EXISTS(SELECT 1 FROM stopwatch_state WHERE stopwatchId = :id)")
    suspend fun instanceExists(id: Int): Boolean
}
```

### **City DAO**
```kotlin
@Dao
interface CityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cities: List<CityEntity>)
    
    @Query("SELECT * FROM cities WHERE timezone_id = :tzId")
    suspend fun getCitiesByTimezone(tzId: String): List<CityEntity>
    
    @Query("SELECT COUNT(*) FROM cities")
    suspend fun getCount(): Int
    
    @Query("DELETE FROM cities")
    suspend fun clearAll()
    
    @Query("SELECT DISTINCT timezone_id FROM cities ORDER BY timezone_id")
    suspend fun getAllTimezones(): List<String>
    
    @Query("SELECT * FROM cities WHERE city_name LIKE :query || '%' OR country_name LIKE :query || '%' LIMIT 20")
    suspend fun searchCities(query: String): List<CityEntity>
}
```

### **TimeZone DAO**
```kotlin
@Dao
interface TimeZoneDao {
    @Query("SELECT * FROM time_zone_boundaries")
    suspend fun getAllBoundaries(): List<TimeZoneBoundaryEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(boundaries: List<TimeZoneBoundaryEntity>)
    
    @Query("SELECT COUNT(*) FROM time_zone_boundaries")
    suspend fun getCount(): Int
    
    @Query("SELECT * FROM time_zone_boundaries WHERE tzid = :tzId")
    suspend fun getBoundaryByTzId(tzId: String): TimeZoneBoundaryEntity?
    
    @Query("DELETE FROM time_zone_boundaries")
    suspend fun clearAll()
}
```

## Database Class

```kotlin
@Database(
    entities = [
        ClockStateEntity::class,
        ClockAlarmEntity::class,
        TimerStateEntity::class,
        StopwatchStateEntity::class,
        CityEntity::class,
        TimeZoneBoundaryEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(PurramidTimeConverters::class)
abstract class PurramidTimeDatabase : RoomDatabase() {
    
    abstract fun clockDao(): ClockDao
    abstract fun clockAlarmDao(): ClockAlarmDao
    abstract fun timerDao(): TimerDao
    abstract fun stopwatchDao(): StopwatchDao
    abstract fun cityDao(): CityDao
    abstract fun timeZoneDao(): TimeZoneDao
    
    companion object {
        @Volatile
        private var INSTANCE: PurramidTimeDatabase? = null
        
        private const val DATABASE_NAME = "purramid_time_db"
        
        fun getInstance(context: Context, isDebugBuild: Boolean = false): PurramidTimeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, isDebugBuild).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context, isDebugBuild: Boolean): PurramidTimeDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                PurramidTimeDatabase::class.java,
                DATABASE_NAME
            )
            
            return if (isDebugBuild) {
                // Destructive migration for DEBUG builds
                builder.fallbackToDestructiveMigration().build()
            } else {
                // Proper migrations for RELEASE builds
                builder.addMigrations(*getAllMigrations()).build()
            }
        }
        
        private fun getAllMigrations(): Array<Migration> {
            return arrayOf(
                // Future migrations will be added here
                // MIGRATION_1_2, MIGRATION_2_3, etc.
            )
        }
    }
}
```

## Type Converters

```kotlin
class PurramidTimeConverters {
    // UUID converters
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()
    
    @TypeConverter
    fun toUUID(uuidString: String?): UUID? = 
        uuidString?.let { UUID.fromString(it) }
    
    // LocalTime converters
    @TypeConverter
    fun fromLocalTime(time: LocalTime?): String? = 
        time?.format(DateTimeFormatter.ISO_LOCAL_TIME)
    
    @TypeConverter
    fun toLocalTime(timeString: String?): LocalTime? = 
        timeString?.let { LocalTime.parse(it, DateTimeFormatter.ISO_LOCAL_TIME) }
    
    // List<String> converter for JSON arrays
    @TypeConverter
    fun fromStringList(value: List<String>?): String = 
        Gson().toJson(value ?: emptyList<String>())
    
    @TypeConverter
    fun toStringList(value: String): List<String> = 
        Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
}
```

## Database Module (Hilt)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PurramidTimeDatabase {
        return PurramidTimeDatabase.getInstance(context, BuildConfig.DEBUG)
    }
    
    @Provides
    fun provideClockDao(database: PurramidTimeDatabase): ClockDao = 
        database.clockDao()
    
    @Provides
    fun provideClockAlarmDao(database: PurramidTimeDatabase): ClockAlarmDao = 
        database.clockAlarmDao()
    
    @Provides
    fun provideTimerDao(database: PurramidTimeDatabase): TimerDao = 
        database.timerDao()
    
    @Provides
    fun provideStopwatchDao(database: PurramidTimeDatabase): StopwatchDao = 
        database.stopwatchDao()
    
    @Provides
    fun provideCityDao(database: PurramidTimeDatabase): CityDao = 
        database.cityDao()
    
    @Provides
    fun provideTimeZoneDao(database: PurramidTimeDatabase): TimeZoneDao = 
        database.timeZoneDao()
}
```

## Initial Data Population

```kotlin
class DatabaseInitializer @Inject constructor(
    private val context: Context,
    private val cityDao: CityDao,
    private val timeZoneDao: TimeZoneDao
) {
    suspend fun initializeDefaults() {
        // Load cities from CSV if database is empty
        if (cityDao.getCount() == 0) {
            loadCitiesFromCsv()
        }
        
        // Load timezone boundaries if database is empty
        if (timeZoneDao.getCount() == 0) {
            loadTimezoneBoundaries()
        }
    }
    
    private suspend fun loadCitiesFromCsv() {
        context.assets.open("cities.csv").use { inputStream ->
            val cities = parseCityCsv(inputStream)
            cityDao.insertAll(cities)
        }
    }
    
    private suspend fun loadTimezoneBoundaries() {
        context.assets.open("timezone_boundaries.json").use { inputStream ->
            val boundaries = parseTimezoneBoundaries(inputStream)
            timeZoneDao.insertAll(boundaries)
        }
    }
}
```

## Migration Examples

```kotlin
// Future migration from version 1 to 2
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Example: Add a new column for clock transparency
        database.execSQL(
            "ALTER TABLE clock_state ADD COLUMN opacity REAL NOT NULL DEFAULT 1.0"
        )
    }
}

// Future migration from version 2 to 3
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Example: Add a new table for clock themes
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS clock_themes (
                theme_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                theme_name TEXT NOT NULL,
                primary_color INTEGER NOT NULL,
                secondary_color INTEGER NOT NULL,
                font_family TEXT NOT NULL
            )
        """)
    }
}
```

## Performance Considerations

### **Query Optimization**
- Indexes on frequently queried columns (instanceId, timezone_id, time, isEnabled)
- Foreign key constraints for data integrity
- LIMIT clauses for search queries
- Flow-based reactive queries for UI updates

### **Memory Management**
- Lazy loading for clock instances
- JSON serialization for complex data (laps, music URLs)
- Efficient bitmap handling for analog clock rendering
- Cleanup of inactive instances on service stop

### **Data Constraints**
- Maximum active clock instances: Limited by system resources
- Maximum alarms per clock: Unlimited (with cascade delete)
- Timer precision: Millisecond accuracy (countdown only)
- Stopwatch precision: Millisecond accuracy with lap tracking
- Timezone data: Pre-loaded from assets
- City database: ~15,000 cities with timezone mapping

### **Background Operations**
- All database operations use suspend functions for coroutines
- Flow emissions for real-time UI updates
- Batch inserts for initial data loading
- Transaction support for complex operations

## Testing Considerations

```kotlin
// In-memory database for testing
@Provides
@Singleton
fun provideTestDatabase(@ApplicationContext context: Context): PurramidTimeDatabase {
    return Room.inMemoryDatabaseBuilder(
        context,
        PurramidTimeDatabase::class.java
    ).allowMainThreadQueries().build()
}
```