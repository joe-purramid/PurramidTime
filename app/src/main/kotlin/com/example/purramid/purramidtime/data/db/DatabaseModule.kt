// DatabaseModule.kt
package com.example.purramid.purramidtime.data.db

import android.content.Context
import com.example.purramid.purramidtime.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers


@Module
@InstallIn(SingletonComponent::class) // Provides dependencies for the entire app lifecycle
object DatabaseModule {

    @Provides
    @Singleton
    fun provideClockAlarmDao(database: PurrTimeDatabase): ClockAlarmDao {
        return database.clockAlarmDao()
    }

    @Provides
    @Singleton
    fun provideClockDao(database: PurrTimeDatabase): ClockDao {
        return database.clockDao()
    }

    @Provides
    @Singleton
    fun provideTimeZoneDao(database: PurrTimeDatabase): TimeZoneDao {
        // Get TimeZoneDao from the PurrTimeDatabase instance
        return database.timeZoneDao()
    }

    @Provides
    @Singleton
    fun provideCityDao(database: PurrTimeDatabase): CityDao {
        return database.cityDao()
    }

    @Provides
    @Singleton
    fun provideSpotlightDao(database: PurrTimeDatabase): StopwatchDao {
        return database.stopwatchDao()
    }

    @Provides
    @Singleton
    fun provideTimerDao(database: PurrTimeDatabase): TimerDao {
        return database.timerDao()
    }

    @Provides
    @Singleton // Ensures only one instance of the Database is created
    fun providePurrTimeDatabase(@ApplicationContext appContext: Context): PurrTimeDatabase {
        return PurrTimeDatabase.getDatabase(appContext)
    }

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}