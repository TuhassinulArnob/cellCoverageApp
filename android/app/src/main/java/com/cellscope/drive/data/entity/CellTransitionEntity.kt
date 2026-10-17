package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cell_transitions",
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
data class CellTransitionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val fromPci: Int?,
    val toPci: Int?,
    val fromRsrp: Int?,
    val toRsrp: Int?,
    val fromTac: Int? = null,
    val toTac: Int? = null,
    val eventType: String, // CONFIRMED_HANDOVER, OBSERVED_TRANSITION, POSSIBLE_HANDOVER, CELL_RESELECTION, SIGNAL_LOSS
    val confidenceScore: Float, // 0.0f .. 1.0f
    val details: String
)
