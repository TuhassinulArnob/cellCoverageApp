package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "network_continuity_events",
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
data class NetworkContinuityEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val fromGeneration: String,    // "5G", "4G", "3G", "2G", "UNKNOWN", "OUT_OF_SERVICE"
    val toGeneration: String,      // "5G", "4G", "3G", "2G", "UNKNOWN", "OUT_OF_SERVICE"
    val fromTech: String?,        // e.g. "4G LTE", "5G NR (NSA EN-DC)", "3G WCDMA/UMTS", "2G EDGE (EGPRS)"
    val toTech: String?,
    val transitionCategory: String, // "DOWNGRADE", "UPGRADE", "OUT_OF_SERVICE", "RECOVERY", "GENERATION_CHANGE"
    val fromSignalDbm: Int?,
    val toSignalDbm: Int?,
    val durationSeconds: Long? = null,
    val dataConnectivityState: String?,
    val evidenceClassification: String, // "CONFIRMED_GENERATION_TRANSITION", "Network generation change — cause unconfirmed", etc.
    val details: String
)
