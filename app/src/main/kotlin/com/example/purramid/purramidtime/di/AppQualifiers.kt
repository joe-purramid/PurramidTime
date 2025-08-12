package com.example.purramid.purramidtime.di

import javax.inject.Qualifier

// Define the qualifier annotation
@Qualifier
@Retention(AnnotationRetention.BINARY) // Standard retention for qualifiers
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClockPrefs // For ClockService SharedPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StopwatchPrefs

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TimersPrefs // For TimersService SharedPreferences
