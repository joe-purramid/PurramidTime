// ClockActivity.kt
package com.example.purramid.purramidtime.clock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.purramid.purramidtime.R
import com.example.purramid.purramidtime.clock.ui.ClockSettingsFragment
import com.example.purramid.purramidtime.clock.viewmodel.ClockViewModel
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.purramid.purramidtime.clock.viewmodel.ClockState

@AndroidEntryPoint
class ClockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClockActivity"
        const val ACTION_SHOW_CLOCK_SETTINGS = "com.example.purramid.clock.ACTION_SHOW_SETTINGS"
        private const val PREFS_KEY_PERMISSION_REQUESTED = "overlay_permission_requested"
    }

    private val clockViewModel: ClockViewModel by viewModels()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check if permission was granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                // Permission granted, start service
                startClockService()
            } else {
                // Permission denied
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "Overlay permission is required for floating clock",
                    Snackbar.LENGTH_LONG
                ).setAction("Grant") {
                    requestOverlayPermission()
                }.show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock)

        // Initialize ViewModel with instance ID if provided
        val instanceId = intent.getIntExtra(ClockOverlayService.EXTRA_CLOCK_ID, -1)
        if (instanceId > 0) {
            clockViewModel.initialize(instanceId)
            observeClockState()
        }

        // Check if this is for showing settings
        if (intent.action == ACTION_SHOW_CLOCK_SETTINGS) {
            showSettingsFragment(instanceId)
        } else {
            // Normal launch - start service
            Log.d(TAG, "Normal launch - starting clock service")

            // Check if service is already running
            val serviceIntent = Intent(this, ClockOverlayService::class.java).apply {
                action = ClockOverlayService.ACTION_START_CLOCK_SERVICE
            }

            // Start the service
            ContextCompat.startForegroundService(this, serviceIntent)

            // Check and request overlay permission if needed
            checkAndRequestOverlayPermission()
        }
    }

    private fun observeClockState() {
        lifecycleScope.launch {
            clockViewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: ClockState) {
        // Update UI elements based on clock state
        // This is primarily for the settings fragment to show current values
    }

    private fun showSettingsFragment(instanceId: Int) {
        if (instanceId > 0) {
            val fragment = ClockSettingsFragment.newInstance(instanceId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.clock_fragment_container, fragment)
                .commit()
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val prefs = getSharedPreferences("clock_prefs", MODE_PRIVATE)
                val hasRequested = prefs.getBoolean(PREFS_KEY_PERMISSION_REQUESTED, false)

                if (!hasRequested) {
                    // First time - auto prompt
                    prefs.edit { putBoolean(PREFS_KEY_PERMISSION_REQUESTED, true) }
                    requestOverlayPermission()
                } else {
                    // Already requested before, show snackbar
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Overlay permission needed for floating clock",
                        Snackbar.LENGTH_LONG
                    ).setAction("Enable") {
                        requestOverlayPermission()
                    }.show()
                }
            } else {
                // Permission already granted, just close the activity
                Log.d(TAG, "Overlay permission already granted, closing activity")
                finish()
            }
        } else {
            // Pre-M, no runtime permission needed - just close
            finish()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startClockService() {
        // Don't start service again if we already started it in onCreate
        Log.d(TAG, "startClockService called - finishing activity")

        // Just close the activity since service was already started
        finish()
    }
}