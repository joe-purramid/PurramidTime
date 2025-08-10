// PurramidTimeApplication.kt
package com.example.purramid.purramidtime

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PurramidTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}