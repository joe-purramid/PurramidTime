// ClockActivity.kt
package com.example.purramid.purramidtime.clock

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.clock.ui.ClockSettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ClockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClockActivity"
        const val ACTION_SHOW_CLOCK_SETTINGS = "com.example.purramid.clock.ACTION_SHOW_SETTINGS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock)

        // Check if service is running, if not start it
        val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
            action = ClockOverlayService.ACTION_START_CLOCK_SERVICE
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // Show settings fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.clock_fragment_container, ClockSettingsFragment())
                .commit()
        }
    }
}