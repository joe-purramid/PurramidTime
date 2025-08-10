// StopwatchState.kt
package com.example.purramid.purramidtime.stopwatch

import com.example.purramid.purramidtime.ui.PurramidPalette
import java.util.UUID

data class StopwatchState(
    val stopwatchId: Int = 0,
    val uuid: UUID = UUID.randomUUID(),
    val currentMillis: Long = 0L,
    val isRunning: Boolean = false,
    val laps: List<Long> = emptyList(),
    val showCentiseconds: Boolean = true,
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    // Window position/size for persistence
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,
    // Nested stopwatch features
    val isNested: Boolean = false,
    val nestedX: Int = -1, // -1 means use default position
    val nestedY: Int = -1,
    // Sound features
    val soundsEnabled: Boolean = false, // For button sounds
    val selectedSoundUri: String? = null, // For lap sound (future feature)
    val musicUrl: String? = null, // For custom music URL (future feature)
    val recentMusicUrls: List<String> = emptyList(), // Last 3 music URLs (future feature)
    // Lap display
    val showLapTimes: Boolean = false
)