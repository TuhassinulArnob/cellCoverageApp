package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.ActiveQoeTestEntity
import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.NetworkGeneration
import java.util.Locale

data class RatMetricsProfile(
    val generation: NetworkGeneration,
    val sampleCount: Int,
    val percentageOfTotal: Double,
    val primaryMetricName: String,
    val primaryMetricUnit: String,
    val medianPrimarySignalDbm: Double?,
    val secondaryMetricName: String?,
    val secondaryMetricUnit: String?,
    val medianSecondaryQuality: Double?,
    val medianDownloadMbps: Double?,
    val medianUploadMbps: Double?,
    val medianLatencyMs: Double?,
    val probeLossPct: Double?,
    val distinctCellCount: Int,
    val isObservedInSession: Boolean,
    val comparabilityWarning: String?
)

data class RatComparisonReport(
    val totalSamples: Int,
    val totalQoeTests: Int,
    val profiles: List<RatMetricsProfile>,
    val dominantGeneration: NetworkGeneration?,
    val comparativeSummary: String
)

object RatComparisonEngine {

    fun generateComparisonReport(
        measurements: List<LteMeasurementEntity>,
        activeTests: List<ActiveQoeTestEntity> = emptyList()
    ): RatComparisonReport {
        val total = measurements.size
        val generations = listOf(
            NetworkGeneration.GEN_5G,
            NetworkGeneration.GEN_4G,
            NetworkGeneration.GEN_3G,
            NetworkGeneration.GEN_2G
        )

        val profiles = generations.map { gen ->
            buildProfileForGeneration(gen, measurements, activeTests, total)
        }

        val dominant = profiles.filter { it.isObservedInSession }
            .maxByOrNull { it.sampleCount }?.generation

        val observedCount = profiles.count { it.isObservedInSession }
        val summary = when {
            total == 0 -> "No survey samples available for cross-RAT evaluation."
            observedCount <= 1 -> "Single-RAT session dominated by ${dominant?.label ?: "Unknown"}. No multi-generation handover or co-channel comparison observed."
            else -> {
                val names = profiles.filter { it.isObservedInSession }.joinToString(" vs ") { it.generation.label }
                "Multi-RAT coexistence observed ($names). Technology metrics isolated with appropriate 3GPP parameters."
            }
        }

        return RatComparisonReport(
            totalSamples = total,
            totalQoeTests = activeTests.size,
            profiles = profiles,
            dominantGeneration = dominant,
            comparativeSummary = summary
        )
    }

    private fun buildProfileForGeneration(
        gen: NetworkGeneration,
        measurements: List<LteMeasurementEntity>,
        activeTests: List<ActiveQoeTestEntity>,
        totalSamples: Int
    ): RatMetricsProfile {
        val matching = measurements.filter {
            val mGen = NetworkGeneration.fromString(it.networkGen ?: it.servingRat ?: it.networkType)
            mGen == gen
        }

        val matchingTests = activeTests.filter { test ->
            val tGen = test.servingRat?.let { NetworkGeneration.fromString(it) }
            tGen == gen
        }

        val count = matching.size
        val isObserved = count > 0 || matchingTests.isNotEmpty()
        val pct = if (totalSamples > 0) Math.round((count.toDouble() / totalSamples * 100.0) * 10.0) / 10.0 else 0.0

        val (primName, primUnit, secName, secUnit) = when (gen) {
            NetworkGeneration.GEN_5G -> Quad("SS-RSRP", "dBm", "SS-SINR", "dB")
            NetworkGeneration.GEN_4G -> Quad("RSRP", "dBm", "SINR", "dB")
            NetworkGeneration.GEN_3G -> Quad("RSCP", "dBm", "Ec/No", "dB")
            NetworkGeneration.GEN_2G -> Quad("RxLev", "dBm", "RxQual / BER", "Level")
            else -> Quad("Signal", "dBm", "Quality", "dB")
        }

        val primSignals = matching.mapNotNull { it.rsrpDbm?.toDouble() }
        val secSignals = matching.mapNotNull { it.sinrDb ?: it.rsrqDb?.toDouble() }

        val dls = matchingTests.mapNotNull { it.downloadSpeedMbps }.filter { it > 0.0 }
        val uls = matchingTests.mapNotNull { it.uploadSpeedMbps }.filter { it > 0.0 }
        val lats = matchingTests.mapNotNull { it.latencyMs?.toDouble() }.filter { it > 0.0 }
        val losses = matchingTests.mapNotNull { it.packetLossPct }

        val cells = matching.mapNotNull { it.servingPci ?: it.earfcn ?: it.nrArfcn }.distinct().size

        val warning = when {
            !isObserved -> "Not observed in survey session (handset or network operated on other RATs)."
            count < 10 -> "Small sample size ($count samples); statistical metrics may carry high variance."
            else -> null
        }

        return RatMetricsProfile(
            generation = gen,
            sampleCount = count,
            percentageOfTotal = pct,
            primaryMetricName = primName,
            primaryMetricUnit = primUnit,
            medianPrimarySignalDbm = computeMedian(primSignals),
            secondaryMetricName = secName,
            secondaryMetricUnit = secUnit,
            medianSecondaryQuality = computeMedian(secSignals),
            medianDownloadMbps = computeMedian(dls),
            medianUploadMbps = computeMedian(uls),
            medianLatencyMs = computeMedian(lats),
            probeLossPct = if (losses.isNotEmpty()) Math.round(losses.average() * 10.0) / 10.0 else null,
            distinctCellCount = cells,
            isObservedInSession = isObserved,
            comparabilityWarning = warning
        )
    }

    private data class Quad(val a: String, val b: String, val c: String?, val d: String?)

    private fun computeMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
        return Math.round(median * 10.0) / 10.0
    }
}
