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
    val errorMessage: String? = null,
    val servingRat: String? = null,
    val servingPci: Int? = null,
    val servingEarfcn: Int? = null,
    val rsrpDbm: Int? = null,
    val sinrDb: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPct: Double? = null,
    val congestionClassification: String? = null,
    val operator: String? = null,
    // Extended v3.0 telemetry fields
    val testStatus: String = if (success) "COMPLETED" else "FAILED",
    val skippedReason: String? = null,
    val scheduledTimestampMs: Long? = null,
    val endTimestampMs: Long? = null,
    val gpsAccuracyM: Float? = null,
    val endpointUrl: String? = null,
    val dlBytes: Long = 0L,
    val dlDurationMs: Long = 0L,
    val ulBytes: Long = 0L,
    val ulDurationMs: Long = 0L,
    val probeCount: Int = 4,
    val successfulProbeCount: Int = 0,
    val p95LatencyMs: Double? = null,
    val meanLatencyMs: Double? = null,
    val rttSamplesJson: String? = null,
    val rsrqDb: Int? = null,
    val activeSimOperator: String? = null,
    val simSlotIndex: Int? = null,
    val dataProvenance: String = "MEASURED"
)
