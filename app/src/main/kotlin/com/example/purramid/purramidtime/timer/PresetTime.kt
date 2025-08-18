// PresetTime.kt
package com.example.purramid.purramidtime.timer

import java.util.UUID

data class PresetTime(
    val id: String = UUID.randomUUID().toString(),
    val durationMillis: Long,
    val backgroundColor: Int,
    val displayOrder: Int // For sorting
)