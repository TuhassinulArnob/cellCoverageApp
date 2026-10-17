package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drive_sessions")
data class DriveSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionName: String,
    val startTimeMs: Long,
    var endTimeMs: Long? = null,
    var sampleCount: Int = 0,
    var notes: String? = null,
    var totalDistanceM: Double = 0.0,
    var maxSpeedKmh: Float = 0f,
    var avgRsrpDbm: Double? = null
)
