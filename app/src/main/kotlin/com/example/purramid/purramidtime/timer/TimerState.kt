// TimerState.kt
package com.example.purramid.purramidtime.timer

import com.example.purramid.purramidtime.ui.PurramidPalette
import java.util.UUID

data class TimerState(
    val timerId: Int = 0,
    val uuid: UUID = UUID.randomUUID(),
    val initialDurationMillis: Long = 0L,
    val currentMillis: Long = 0L,
    val isRunning: Boolean = false,
    val playSoundOnEnd: Boolean = false,
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,

    // Window position/size for persistence
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,

    // Nested timer features
    val isNested: Boolean = false,
    val nestedX: Int = -1, // -1 means use default position
    val nestedY: Int = -1,

    // Sound features
    val selectedSoundUri: String? = null, // For countdown finish sound
    val musicUrl: String? = null, // For custom music URL
    val recentMusicUrls: List<String> = emptyList(), // Last 3 music URLs
)