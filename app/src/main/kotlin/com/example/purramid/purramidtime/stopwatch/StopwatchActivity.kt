// StopwatchActivity.kt
package com.example.purramid.purramidtime.stopwatch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.databinding.ActivityStopwatchBinding
import com.example.purramid.purramidtime.instance.InstanceManager
import com.example.purramid.purramidtime.stopwatch.ui.StopwatchSettingsFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class StopwatchActivity : AppCompatActivity() {

    @Inject lateinit var instanceManager: InstanceManager
    private lateinit var binding: ActivityStopwatchBinding

    companion object {
        private const val TAG = "StopwatchActivity"
        const val ACTION_SHOW_STOPWATCH_SETTINGS = "com.example.purramid.purramidtime.stopwatch.ACTION_SHOW_STOPWATCH_SETTINGS"
    }

    private var currentStopwatchId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStopwatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate - Intent Action: ${intent.action}")

        currentStopwatchId = intent.getIntExtra(StopwatchService.EXTRA_STOPWATCH_ID, 0)

        if (intent.action == ACTION_SHOW_STOPWATCH_SETTINGS) {
            if (currentStopwatchId != 0) {
                showSettingsFragment(currentStopwatchId)
            } else {
                Log.e(TAG, "Cannot show settings, invalid stopwatchId: $currentStopwatchId")
                finish()
            }
        } else {
            // Default action: launch a new stopwatch service instance
            if (canCreateNewInstance()) {
                Log.d(TAG, "Launching stopwatch service")
                startStopwatchService(currentStopwatchId)
                finish()
            }
        }
    }

    private fun canCreateNewInstance(): Boolean {
        if (currentStopwatchId != 0) {
            // Existing stopwatch ID, not creating new
            return true
        }

        val activeCount = instanceManager.getActiveInstanceCount(InstanceManager.STOPWATCH)
        if (activeCount >= 4) {
            // Show Snackbar with the maximum reached message
            Snackbar.make(
                binding.root,
                getString(R.string.max_stopwatches_reached_snackbar),
                Snackbar.LENGTH_LONG
            ).show()

            // Delay finish to allow Snackbar to be visible
            binding.root.postDelayed({ finish() }, 2000)
            return false
        }
        return true
    }

    private fun startStopwatchService(stopwatchId: Int) {
        Log.d(TAG, "Requesting start for StopwatchService, ID: $stopwatchId")
        val serviceIntent = Intent(this, StopwatchService::class.java).apply {
            action = StopwatchService.ACTION_START_STOPWATCH
            if (stopwatchId != 0) {
                putExtra(StopwatchService.EXTRA_STOPWATCH_ID, stopwatchId)
            }
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun showSettingsFragment(stopwatchId: Int) {
        if (supportFragmentManager.findFragmentByTag(StopwatchSettingsFragment.TAG) == null) {
            Log.d(TAG, "Showing settings fragment for stopwatchId: $stopwatchId")
            StopwatchSettingsFragment.newInstance(stopwatchId).show(
                supportFragmentManager, StopwatchSettingsFragment.TAG
            )
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent - Action: ${intent?.action}")
        if (intent?.action == ACTION_SHOW_STOPWATCH_SETTINGS) {
            val stopwatchIdForSettings = intent.getIntExtra(StopwatchService.EXTRA_STOPWATCH_ID, 0)
            if (stopwatchIdForSettings != 0) {
                currentStopwatchId = stopwatchIdForSettings
                showSettingsFragment(stopwatchIdForSettings)
            } else {
                Log.e(TAG, "Cannot show settings from onNewIntent, invalid stopwatchId.")
            }
        }
    }
}