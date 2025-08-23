// TimerActivity.kt
package com.example.purramid.purramidtime.timer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.databinding.ActivityTimerBinding
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.timer.ui.TimerSettingsFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TimerActivity : AppCompatActivity() {

    @Inject lateinit var instanceManager: InstanceManager
    private lateinit var binding: ActivityTimerBinding

    companion object {
        private const val TAG = "TimerActivity"
        const val ACTION_SHOW_TIMER_SETTINGS = "com.example.purramid.purramidtime.timer.ACTION_SHOW_TIMER_SETTINGS"
        const val ACTION_SHOW_PRESETS = "com.example.purramid.purramidtime.timer.ACTION_SHOW_PRESETS"
    }

    private var currentTimerId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        currentTimerId = intent.getIntExtra(EXTRA_TIMER_ID, 0)

        when (intent.action) {
            ACTION_SHOW_TIMER_SETTINGS -> {
                if (currentTimerId != 0) {
                    showSettingsFragment(currentTimerId)
                } else {
                    Log.e(TAG, "Cannot show settings, invalid timerId: 0")
                    finish()
                }
            }
            ACTION_SHOW_PRESETS -> {
                if (currentTimerId != 0) {
                    showPresetsDialog(currentTimerId)
                } else {
                    Log.e(TAG, "Cannot show presets, invalid timerId: 0")
                    finish()
                }
            }
            else -> {
                // Default action: launch a new timer service instance
                if (canCreateNewInstance()) {
                    Log.d(TAG, "Launching timer service")
                    startTimerService(currentTimerId)
                    finish()
                }
            }
        }
    }

    private fun canCreateNewInstance(): Boolean {
        if (currentTimerId != 0) {
            // Existing timer ID, not creating new
            return true
        }

        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMER)
        if (activeCount >= 4) {
            // Show Snackbar with the maximum reached message
            Snackbar.make(
                binding.root,
                getString(R.string.max_timers_reached_snackbar),
                Snackbar.LENGTH_LONG
            ).show()

            // Delay finish to allow Snackbar to be visible
            binding.root.postDelayed({ finish() }, 2000)
            return false
        }
        return true
    }

    private fun startTimerService(timerId: Int, durationMs: Long? = null) {
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            action = ACTION_START_TIMER
            if (timerId != 0) {
                putExtra(EXTRA_TIMER_ID, timerId)
            }
            durationMs?.let { putExtra(EXTRA_DURATION_MS, it) }
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun showSettingsFragment(timerId: Int) {
        if (supportFragmentManager.findFragmentByTag(TimerSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing settings fragment for timerId: $timerId")
            TimerSettingsFragment.newInstance(timerId).show(
                supportFragmentManager, TimerSettingsFragment.TAG
            )
        }
    }

    private fun showPresetsDialog(timerId: Int) {
        // This would show the presets dialog
        // Implementation would be similar to showSettingsFragment
        Log.d(TAG, "Showing presets dialog for timerId: $timerId")
        // TODO: Implement preset dialog showing
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")

        when (intent?.action) {
            ACTION_SHOW_TIMER_SETTINGS -> {
                val timerIdForSettings = intent.getIntExtra(EXTRA_TIMER_ID, 0)
                if (timerIdForSettings != 0) {
                    currentTimerId = timerIdForSettings
                    showSettingsFragment(timerIdForSettings)
                } else {
                    Log.e(TAG, "Cannot show settings from onNewIntent, invalid timerId.")
                }
            }
            ACTION_SHOW_PRESETS -> {
                val timerIdForPresets = intent.getIntExtra(EXTRA_TIMER_ID, 0)
                if (timerIdForPresets != 0) {
                    currentTimerId = timerIdForPresets
                    showPresetsDialog(timerIdForPresets)
                } else {
                    Log.e(TAG, "Cannot show presets from onNewIntent, invalid timerId.")
                }
            }
        }
    }
}