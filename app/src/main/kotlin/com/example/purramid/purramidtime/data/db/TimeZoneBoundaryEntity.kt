package com.example.purramid.purramidtime.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "time_zone_boundaries")
data class TimeZoneBoundaryEntity(
    @PrimaryKey val tzid: String,
    val polygonWkt: String
)