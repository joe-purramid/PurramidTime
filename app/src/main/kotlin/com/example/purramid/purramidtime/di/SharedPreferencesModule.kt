// SharedPreferencesModule.kt
package com.example.purramid.purramidtime.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ClockPrefs
    fun provideClockPreferences(@ApplicationContext context: Context): SharedPreferences {
        // Assuming ClockOverlayService.PREFS_NAME_FOR_ACTIVITY exists
        return context.getSharedPreferences(com.example.purramid.purramidtime.clock.ClockOverlayService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @StopwatchPrefs
    fun provideStopwatchPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(com.example.purramid.purramidtime.stopwatch.StopwatchService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    @TimerPrefs
    fun provideTimerPreferences(@ApplicationContext context: Context): SharedPreferences {
         // Assuming TimerService.PREFS_NAME_FOR_ACTIVITY exists
        return context.getSharedPreferences(com.example.purramid.purramidtime.timer.TimerService.PREFS_NAME_FOR_ACTIVITY, Context.MODE_PRIVATE)
    }
}