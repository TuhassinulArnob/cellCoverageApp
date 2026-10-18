package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "service_holes",
    foreignKeys = [
        ForeignKey(
            entity = DriveSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestampMs"])
    ]
)
data class ServiceHoleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val holeType: String, // "RADIO_OUT_OF_SERVICE", "CELLULAR_DATA_DISCONNECTED", "INTERNET_TEST_FAILURE", "POOR_INTERNET_PERFORMANCE", "WEAK_RADIO_SIGNAL", "TELEMETRY_GAP", "GENERATION_DOWNGRADE"
    val severity: String, // "CRITICAL", "HIGH", "MEDIUM", "LOW"
    val durationMs: Long? = null,
    val recoveryTimestampMs: Long? = null,
    val rsrpDbm: Int? = null,
    val networkType: String? = null,
    val networkGen: String? = null,
    val engineeringEvidence: String,
    val resolutionStatus: String = "OBSERVED" // "OBSERVED", "RECOVERED", "ONGOING"
)
