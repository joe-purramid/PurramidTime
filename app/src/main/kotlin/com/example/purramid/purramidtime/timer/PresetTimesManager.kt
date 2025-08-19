// PresetTimesManager.kt
package com.example.purramid.purramidtime.timer

import android.content.Context
import com.example.purramid.purramidtime.ui.PurramidPalette
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class PresetTimesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("preset_times_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_PRESET_TIMES = "preset_times"
        private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized"
        private const val MAX_PRESET_TIMES = 8

        // Default preset times in milliseconds
        private const val DEFAULT_TIME_1_MINUTE = 60000L      // 0:01:00
        private const val DEFAULT_TIME_15_MINUTES = 900000L   // 0:15:00
        private const val DEFAULT_TIME_1_HOUR = 3600000L      // 1:00:00
    }

    init {
        // Initialize default preset times on first launch
        initializeDefaultsIfNeeded()
    }

    private fun initializeDefaultsIfNeeded() {
        val defaultsInitialized = prefs.getBoolean(KEY_DEFAULTS_INITIALIZED, false)

        if (!defaultsInitialized) {
            // Create default preset times
            val defaultPresets = listOf(
                PresetTime(
                    id = UUID.randomUUID().toString(),
                    durationMillis = DEFAULT_TIME_1_MINUTE,
                    backgroundColor = PurramidPalette.WHITE.colorInt,
                    displayOrder = 0
                ),
                PresetTime(
                    id = UUID.randomUUID().toString(),
                    durationMillis = DEFAULT_TIME_15_MINUTES,
                    backgroundColor = PurramidPalette.WHITE.colorInt,
                    displayOrder = 1
                ),
                PresetTime(
                    id = UUID.randomUUID().toString(),
                    durationMillis = DEFAULT_TIME_1_HOUR,
                    backgroundColor = PurramidPalette.WHITE.colorInt,
                    displayOrder = 2
                )
            )

            // Save default presets
            savePresetTimes(defaultPresets)

            // Mark defaults as initialized
            prefs.edit { putBoolean(KEY_DEFAULTS_INITIALIZED, true) }
        }
    }

    fun getPresetTimes(): List<PresetTime> {
        val json = prefs.getString(KEY_PRESET_TIMES, "[]")
        return try {
            val type = object : TypeToken<List<PresetTime>>() {}.type
            val times = gson.fromJson<List<PresetTime>>(json, type) ?: emptyList()
            // Sort by duration (lowest first)
            times.sortedBy { it.durationMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun addPresetTime(durationMillis: Long, backgroundColor: Int): Boolean {
        val currentTimes = getPresetTimes().toMutableList()
        
        // Check if already at max
        if (currentTimes.size >= MAX_PRESET_TIMES) {
            return false
        }
        
        // Check if this duration already exists
        if (currentTimes.any { it.durationMillis == durationMillis }) {
            return false
        }
        
        // Add new preset time
        val newPreset = PresetTime(
            durationMillis = durationMillis,
            backgroundColor = backgroundColor,
            displayOrder = currentTimes.size
        )
        currentTimes.add(newPreset)
        
        // Save
        savePresetTimes(currentTimes)
        return true
    }
    
    fun removePresetTime(id: String) {
        val currentTimes = getPresetTimes().toMutableList()
        currentTimes.removeAll { it.id == id }

        // Re-order remaining presets
        currentTimes.forEachIndexed { index, presetTime ->
            currentTimes[index] = presetTime.copy(displayOrder = index)
        }

        savePresetTimes(currentTimes)
    }
    
    fun canAddMore(): Boolean {
        return getPresetTimes().size < MAX_PRESET_TIMES
    }

    fun resetToDefaults() {
        // Clear current presets and reinitialize defaults
        prefs.edit { remove(KEY_PRESET_TIMES) }
        prefs.edit { remove(KEY_DEFAULTS_INITIALIZED) }
        initializeDefaultsIfNeeded()
    }

    private fun savePresetTimes(times: List<PresetTime>) {
        val json = gson.toJson(times)
        prefs.edit { putString(KEY_PRESET_TIMES, json) }
    }
}