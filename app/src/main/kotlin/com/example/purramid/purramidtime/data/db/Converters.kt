// Converters.kt
package com.example.purramid.purramidtime.data.db

import android.util.Log
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalTime
import java.util.UUID

/**
 * Type converters to allow Room to reference complex data types.
 */
class Converters {

    private val gson = Gson() // Define Gson instance for use in converters

    /**
     * Converts a UUID? to a String? for storing in the database.
     * Returns null if the UUID is null.
     */
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? {
        return uuid?.toString()
    }

    /**
     * Converts a String? back to a UUID? when reading from the database.
     * Returns null if the String is null or empty.
     */
    @TypeConverter
    fun toUUID(uuidString: String?): UUID? {
        // Check for null or empty string before attempting conversion
        return if (uuidString.isNullOrEmpty()) {
            null
        } else {
            try {
                UUID.fromString(uuidString)
            } catch (e: IllegalArgumentException) {
                // Handle potential malformed UUID strings if necessary,
                Log.e("Converters", "Could not convert string to UUID: $uuidString", e)
                null // Or throw an exception, depending on desired error handling
            }
        }
    }

    /**
     * Converts a List<String>? (e.g., for emojiList) to a JSON String? for storing.
     * Uses Gson for serialization. Returns null if the list is null.
     */
    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let {
            try {
                Gson().toJson(it)
            } catch (e: Exception) {
                Log.e("Converters", "Could not convert List<String> to JSON", e)
                null
            }
        }
    }

    /**
     * Converts a JSON String? back to a List<String>? when reading from the database.
     * Uses Gson for deserialization. Returns null if the string is null or empty.
     */
    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return if (value.isNullOrEmpty()) {
            null
        } else {
            try {
                val listType = object : TypeToken<List<String>>() {}.type
                Gson().fromJson(value, listType)
            } catch (e: Exception) {
                // Handle potential JSON parsing errors
                Log.e("Converters", "Could not convert JSON to List<String>: $value", e)
                null // Or return emptyList(), depending on desired error handling
            }
        }
    }

    // --- CLOCK ---
    /**
     * Converts a LocalTime to seconds of day (Long) for storage.
     * Returns null if the LocalTime is null.
     */
    @TypeConverter
    fun fromLocalTime(time: LocalTime?): Long? {
        return time?.toSecondOfDay()?.toLong()
    }

    /**
     * Converts seconds of day (Long) back to LocalTime.
     * Returns null if the input is null.
     */
    @TypeConverter
    fun toLocalTime(seconds: Long?): LocalTime? {
        return seconds?.let {
            try {
                LocalTime.ofSecondOfDay(it)
            } catch (e: Exception) {
                Log.e("Converters", "Invalid seconds value for LocalTime: $seconds", e)
                null
            }
        }
    }

    // --- TIMER ---
    @TypeConverter
    fun fromTimerType(value: TimerType?): String? {
        return value?.name
    }

    @TypeConverter
    fun toTimerType(value: String?): TimerType? {
        return try {
            value?.let { TimerType.valueOf(it) }
        } catch (e: IllegalArgumentException) {
            Log.e("Converters", "Invalid TimerType string: $value", e)
            null
        }
    }

}