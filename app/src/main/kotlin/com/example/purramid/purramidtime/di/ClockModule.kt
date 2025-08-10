// di/ClockModule.kt
package com.example.purramid.purramidtime.di

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClockPrefs

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    @Singleton
    fun provideWindowManager(@ApplicationContext context: Context): WindowManager {
        return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @Provides
    @Singleton
    @ClockPrefs
    fun provideClockPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)
    }
}