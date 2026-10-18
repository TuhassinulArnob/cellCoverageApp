package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_reliability_events",
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
data class CallReliabilityEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val callState: String, // "IDLE", "RINGING", "OFFHOOK"
    val classification: String, // "NORMAL_CALL_START", "NORMAL_CALL_END", "SUSPECTED_CALL_DROP", "UNCLASSIFIED_TERMINATION", "TERMINATION_WITH_SERVICE_INTERRUPTION"
    val previousState: String?,
    val networkGeneration: String?,
    val detailedTech: String?,
    val rsrpDbm: Int?,
    val serviceState: String?,
    val callDurationSec: Long? = null,
    val riskScoreCategory: String, // "LOW", "MODERATE", "HIGH", "SEVERE", "INSUFFICIENT_EVIDENCE"
    val evidenceFactors: String
)
