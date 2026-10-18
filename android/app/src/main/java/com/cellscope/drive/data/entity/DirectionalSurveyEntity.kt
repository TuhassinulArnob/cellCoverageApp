package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "directional_surveys",
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
data class DirectionalSurveyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val cellId: Int,
    val networkGen: String,
    val sampleCount: Int,
    val headingSpanDeg: Float,
    val estimatedHeadingDeg: Float?,
    val uncertaintySectorDeg: Float?,
    val confidenceScore: Float,
    val confidenceTier: String,
    val samplesJson: String,
    val disclaimer: String
)
