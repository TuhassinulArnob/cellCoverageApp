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
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedKmh: Float? = null,
    val networkType: String = "LTE",
    val operatorAlpha: String? = null,
    val operatorNumeric: String? = null,
    val servingPci: Int? = null,
    val tac: Int? = null,
    val earfcn: Int? = null,
    val bandwidthKhz: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val rssiDbm: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val isServing: Boolean = true,
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
    val headingValid: Boolean = false,
    val servingRat: String? = null,
    val channelNumber: Int? = null,
    val channelType: String? = null,
    val primarySignalDbm: Int? = null,
    val signalMetricType: String? = null,
    val secondarySignalDb: Double? = null,
    val sinrSource: String = "UNAVAILABLE",
    val estimationMethod: String? = null,
    val estimationAssumptions: String? = null,
    val sinrConfidence: String? = null,
    val qualitativeDiagnostic: String? = null,
    val impliedRssiDbm: Double? = null
)

