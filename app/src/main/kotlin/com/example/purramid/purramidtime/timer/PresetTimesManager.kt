// PresetTimesManager.kt
package com.example.purramid.purramidtime.timer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetTimesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("preset_times_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_PRESET_TIMES = "preset_times"
        private const val MAX_PRESET_TIMES = 8
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
        savePresetTimes(currentTimes)
    }
    
    fun canAddMore(): Boolean {
        return getPresetTimes().size < MAX_PRESET_TIMES
    }
    
    private fun savePresetTimes(times: List<PresetTime>) {
        val json = gson.toJson(times)
        prefs.edit().putString(KEY_PRESET_TIMES, json).apply()
    }
}