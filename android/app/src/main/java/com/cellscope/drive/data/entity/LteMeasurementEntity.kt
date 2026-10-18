package com.cellscope.drive.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lte_measurements",
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
data class LteMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestampIso: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val speedKmh: Float?,
    val networkType: String,
    val operatorAlpha: String?,
    val operatorNumeric: String?,
    val servingPci: Int?,
    val tac: Int?,
    val earfcn: Int?,
    val bandwidthKhz: Int?,
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val rssiDbm: Int?,
    val cqi: Int?,
    val timingAdvance: Int?,
    val isServing: Boolean,
    val handoverTrigger: Int = 0,
    val bearing: Float? = null,
    val sinrDb: Double? = null,
    val eci: Int? = null,
    val neighborCellsJson: String? = null,
    val transitionType: String? = null,
    val isGpsValid: Boolean = true,
    val nci: Long? = null,        // 36-bit NR Cell Identity (5G NR)
    val nrArfcn: Int? = null,    // NR-ARFCN (5G NR)
    val csiRsrp: Int? = null,    // CSI-RSRP (5G NR)
    val csiSinr: Double? = null, // CSI-SINR (5G NR)
    val dataNetworkType: String? = null,
    val voiceNetworkType: String? = null,
    val networkGen: String? = null,
    val serviceState: String? = null,
    val dataActivityState: String? = null,
    val headingDeg: Float? = null,
    val magneticHeadingDeg: Float? = null,
    val headingAccuracyDeg: Float? = null,
    val orientationSensorType: String? = null,
    val headingValid: Boolean = false
)

