// src/main/kotlin/com/example/purramid/thepurramid/timers/TimersActivity.kt
package com.example.purramid.thepurramid.timers

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ActivityTimersBinding
import com.example.purramid.thepurramid.instance.InstanceManager
import com.example.purramid.thepurramid.timers.ui.TimersSettingsFragment
import com.example.purramid.thepurramid.timers.viewmodel.TimersViewModel
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TimersActivity : AppCompatActivity() {

    @Inject lateinit var instanceManager: InstanceManager
    private lateinit var binding: ActivityTimersBinding

    companion object {
        private const val TAG = "TimersActivity"
        const val ACTION_SHOW_TIMER_SETTINGS = "com.example.purramid.timers.ACTION_SHOW_TIMER_SETTINGS"
    }

    private var currentTimerId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        currentTimerId = intent.getIntExtra(EXTRA_TIMER_ID, 0)

        if (intent.action == ACTION_SHOW_TIMER_SETTINGS) {
            if (currentTimerId != 0) {
                showSettingsFragment(currentTimerId)
            } else {
                Log.e(TAG, "Cannot show settings, invalid timerId: $currentTimerId")
                finish()
            }
        } else {
            // Default action: launch a new timer service instance
            if (canCreateNewInstance()) {
                Log.d(TAG, "Launching timer service")
                startTimerService(currentTimerId, TimerType.STOPWATCH)
                finish()
            }
        }
    }

    private fun canCreateNewInstance(): Boolean {
        if (currentTimerId != 0) {
            // Existing timer ID, not creating new
            return true
        }

        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.TIMERS)
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

    private fun startTimerService(timerId: Int, type: TimerType, durationMs: Long? = null) {
        Log.d(TAG, "Requesting start for TimerService, ID: $timerId, Type: $type")
        val serviceIntent = Intent(this, TimersService::class.java).apply {
            action = if (type == TimerType.COUNTDOWN) {
                ACTION_START_COUNTDOWN
            } else {
                ACTION_START_STOPWATCH
            }
            if (timerId != 0) {
                putExtra(EXTRA_TIMER_ID, timerId)
            }
            durationMs?.let { putExtra(EXTRA_DURATION_MS, it) }
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun showSettingsFragment(timerId: Int) {
        if (supportFragmentManager.findFragmentByTag(TimersSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing settings fragment for timerId: $timerId")
            TimersSettingsFragment.newInstance(timerId).show(
                supportFragmentManager, TimersSettingsFragment.TAG
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_TIMER_SETTINGS) {
            val timerIdForSettings = intent.getIntExtra(EXTRA_TIMER_ID, 0)
            if (timerIdForSettings != 0) {
                currentTimerId = timerIdForSettings
                showSettingsFragment(timerIdForSettings)
            } else {
                Log.e(TAG, "Cannot show settings from onNewIntent, invalid timerId.")
            }
        }
    }
}