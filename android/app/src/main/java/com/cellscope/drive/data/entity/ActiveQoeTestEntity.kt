package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "active_qoe_tests",
    foreignKeys = [
        ForeignKey(
            entity = DriveSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ActiveQoeTestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val testType: String, // PING, DOWNLOAD, UPLOAD, FULL_CYCLE
    val latencyMs: Long? = null,
    val downloadSpeedMbps: Double? = null,
    val uploadSpeedMbps: Double? = null,
    val bytesTransferred: Long = 0L,
    val durationMs: Long = 0L,
    val success: Boolean,
    val httpStatusCode: Int? = null,
    val errorMessage: String? = null
)
