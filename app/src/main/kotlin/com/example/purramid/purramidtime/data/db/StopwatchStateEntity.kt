// StopwatchStateEntity.kt
package com.example.purramid.purramidtime.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.purramid.purramidtime.ui.PurramidPalette
import java.util.UUID

@Entity(tableName = "stopwatch_state")
data class StopwatchStateEntity(
    @PrimaryKey
    val stopwatchId: Int,
    val uuid: String = UUID.randomUUID().toString(),
    val currentMillis: Long = 0L,
    val isRunning: Boolean = false,
    val lapsJson: String = "[]",
    val showCentiseconds: Boolean = true,
    val overlayColor: Int = PurramidPalette.WHITE.colorInt,
    val windowX: Int = 0,
    val windowY: Int = 0,
    val windowWidth: Int = -1,
    val windowHeight: Int = -1,

    // Sound fields
    val soundsEnabled: Boolean = false,
    val selectedSoundUri: String? = null,
    val musicUrl: String? = null,
    val recentMusicUrlsJson: String = "[]",

    // Lap display
    val showLapTimes: Boolean = false,
)