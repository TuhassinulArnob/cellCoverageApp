package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.util.Locale
import kotlin.math.roundToInt

object RadioQualityEngine {

    data class HistogramBucket(
        val label: String,
        val count: Int,
        val percentage: Double
    )

    data class MetricDistribution(
        val name: String,
        val unit: String,
        val min: Double?,
        val max: Double?,
        val mean: Double?,
        val median: Double?,
        val buckets: List<HistogramBucket>
    )

    data class NeighborDeltaSummary(
        val totalEvaluatedSamples: Int,
        val avgDeltaRsrpDb: Double?,
        val nearHandoverMarginCount: Int, // Delta < 3 dB
        val overdueHandoverCount: Int,    // Delta < 0 dB (neighbor stronger than server)
        val pilotPollutionCount: Int      // >= 3 strong competitors within 5 dB
    )

    data class DownlinkInterferenceEstimate(
        val avgEstimatedInterferenceDbm: Double?,
        val highInterferenceRatioPct: Double,
        val pilotPollutionRatioPct: Double,
        val interpretationSummary: String
    )

    data class SignalQualityAudit(
        val totalSamples: Int,
        val validRsrpCount: Int,
        val missingOrInvalidCount: Int,
        val suspectClampCount: Int,
        val suspectClampPercent: Double,
        val rawMeanRsrp: Double?,
        val validatedMeanRsrp: Double?,
        val rawMedianRsrp: Double?,
        val validatedMedianRsrp: Double?,
        val excludedSampleCount: Int,
        val exclusionReasons: List<String>,
        val calibrationStatus: String = "No laboratory calibration offset applied — raw hardware measurements preserved",
        val auditExplanation: String
    ) {
        // Backward-compatible properties for existing callers
        val meanRsrpRaw: Double? get() = rawMeanRsrp
        val meanRsrpCalibrated: Double? get() = validatedMeanRsrp
    }

    data class RadioQualityReport(
        val rsrpDistribution: MetricDistribution,
        val rsrqDistribution: MetricDistribution,
        val sinrDistribution: MetricDistribution,
        val neighborDelta: NeighborDeltaSummary,
        val interferenceEstimate: DownlinkInterferenceEstimate,
        val signalAudit: SignalQualityAudit? = null,
        val gsmRssiDistribution: MetricDistribution? = null,
        val wcdmaRscpDistribution: MetricDistribution? = null,
        val nrSsRsrpDistribution: MetricDistribution? = null
    )

    fun evaluateSessionQuality(measurements: List<LteMeasurementEntity>): RadioQualityReport {
        // Strict LTE samples isolation: exclude 2G GSM and 3G WCDMA from RSRP distribution
        val lteMeasurements = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            !rat.contains("2G", ignoreCase = true) && !rat.contains("GSM", ignoreCase = true) &&
            !rat.contains("3G", ignoreCase = true) && !rat.contains("WCDMA", ignoreCase = true) && !rat.contains("UMTS", ignoreCase = true)
        }
        val rsrpValues = lteMeasurements.mapNotNull { it.rsrpDbm?.toDouble() }
        val rsrqValues = lteMeasurements.mapNotNull { it.rsrqDb?.toDouble() }
        val sinrValues = lteMeasurements.mapNotNull { it.sinrDb }

        val rsrpDist = computeRsrpDistribution(rsrpValues)
        val rsrqDist = computeRsrqDistribution(rsrqValues)
        val sinrDist = computeSinrDistribution(sinrValues)

        // 2G GSM RSSI distribution
        val gsmMeasurements = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true) || rat.contains("EDGE", ignoreCase = true)
        }
        val gsmRssiValues = gsmMeasurements.mapNotNull { (it.primarySignalDbm ?: it.rssiDbm)?.toDouble() }
        val gsmDist = if (gsmRssiValues.isNotEmpty()) computeRssiDistribution("2G GSM RSSI", gsmRssiValues) else null

        // 3G WCDMA RSCP distribution
        val wcdmaMeasurements = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            rat.contains("3G", ignoreCase = true) || rat.contains("WCDMA", ignoreCase = true) || rat.contains("UMTS", ignoreCase = true)
        }
        val wcdmaRscpValues = wcdmaMeasurements.mapNotNull { (it.primarySignalDbm ?: it.rssiDbm)?.toDouble() }
        val wcdmaDist = if (wcdmaRscpValues.isNotEmpty()) computeRscpDistribution("3G WCDMA RSCP", wcdmaRscpValues) else null

        // 5G NR SS-RSRP distribution
        val nrMeasurements = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            rat.contains("5G", ignoreCase = true) || rat.contains("NR", ignoreCase = true)
        }
        val nrSsRsrpValues = nrMeasurements.mapNotNull { (it.primarySignalDbm ?: it.rsrpDbm)?.toDouble() }
        val nrDist = if (nrSsRsrpValues.isNotEmpty()) computeRsrpDistribution(nrSsRsrpValues).copy(name = "5G NR SS-RSRP") else null

        val neighborDelta = computeNeighborDelta(measurements)
        val interference = computeInterference(measurements, neighborDelta)
        val audit = auditSignalQuality(measurements)

        return RadioQualityReport(
            rsrpDistribution = rsrpDist,
            rsrqDistribution = rsrqDist,
            sinrDistribution = sinrDist,
            neighborDelta = neighborDelta,
            interferenceEstimate = interference,
            signalAudit = audit,
            gsmRssiDistribution = gsmDist,
            wcdmaRscpDistribution = wcdmaDist,
            nrSsRsrpDistribution = nrDist
        )
    }

    fun isSuspectBasebandClamp(
        rsrp: Int?,
        rsrq: Int?,
        rssi: Int?,
        networkType: String? = null,
        servingRat: String? = null
    ): Boolean {
        val rat = servingRat ?: networkType ?: ""
        if (rat.contains("2G", ignoreCase = true) || rat.contains("GSM", ignoreCase = true)) {
            // In 2G, -44 dBm is typical RSSI near a tower, not an LTE driver clamp!
            return false
        }
        return rsrp == -44 && rsrq == -3 && (rssi == null || rssi <= -100)
    }

    fun auditSignalQuality(measurements: List<LteMeasurementEntity>): SignalQualityAudit {
        val total = measurements.size
        val lteOnly = measurements.filter {
            val rat = it.servingRat ?: it.networkGen ?: it.networkType
            !rat.contains("2G", ignoreCase = true) && !rat.contains("GSM", ignoreCase = true) &&
            !rat.contains("3G", ignoreCase = true) && !rat.contains("WCDMA", ignoreCase = true)
        }
        val rawRsrps = lteOnly.mapNotNull { it.rsrpDbm?.toDouble() }
        val validRsrpCount = lteOnly.count { it.rsrpDbm != null && it.rsrpDbm in -140..-44 }
        val missingOrInvalidCount = lteOnly.size - validRsrpCount
        
        val suspectCount = measurements.count { isSuspectBasebandClamp(it.rsrpDbm, it.rsrqDb, it.rssiDbm, it.networkType, it.servingRat) }
        val suspectPct = if (total > 0) (suspectCount.toDouble() / total.toDouble()) * 100.0 else 0.0

        val validatedRsrps = lteOnly.filterNot { isSuspectBasebandClamp(it.rsrpDbm, it.rsrqDb, it.rssiDbm, it.networkType, it.servingRat) }
            .mapNotNull { it.rsrpDbm?.toDouble() }
            .filter { it in -140.0..-44.0 }

        val meanRaw = if (rawRsrps.isNotEmpty()) (rawRsrps.average() * 10.0).roundToInt() / 10.0 else null
        val meanValidated = if (validatedRsrps.isNotEmpty()) (validatedRsrps.average() * 10.0).roundToInt() / 10.0 else null

        fun calcMedian(list: List<Double>): Double? {
            if (list.isEmpty()) return null
            val s = list.sorted()
            val m = if (s.size % 2 == 1) {
                s[s.size / 2]
            } else {
                (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            }
            return (m * 10.0).roundToInt() / 10.0
        }

        val rawMedian = calcMedian(rawRsrps)
        val validatedMedian = calcMedian(validatedRsrps)

        val exclusionReasons = mutableListOf<String>()
        if (suspectCount > 0) {
            exclusionReasons.add("$suspectCount sample(s) clamped at 3GPP TS 36.133 index 97 ceiling (-44 dBm RSRP, -3 dB RSRQ)")
        }
        if (missingOrInvalidCount > 0) {
            exclusionReasons.add("$missingOrInvalidCount sample(s) with unobservable or out-of-range RSRP")
        }

        val explanation = if (suspectCount > 0) {
            "$suspectCount sample(s) exhibit baseband modem index-97 clamping (-44 dBm RSRP with -3 dB RSRQ and <= -100 dBm RSSI). Raw hardware telemetry is preserved in database without mutation. Validated mean LTE RSRP is ${meanValidated ?: "—"} dBm (median: ${validatedMedian ?: "—"} dBm) vs raw mean ${meanRaw ?: "—"} dBm."
        } else {
            "100% telemetry integrity verified. No suspect baseband clamp flatlines detected."
        }

        return SignalQualityAudit(
            totalSamples = total,
            validRsrpCount = validRsrpCount,
            missingOrInvalidCount = missingOrInvalidCount,
            suspectClampCount = suspectCount,
            suspectClampPercent = (suspectPct * 10.0).roundToInt() / 10.0,
            rawMeanRsrp = meanRaw,
            validatedMeanRsrp = meanValidated,
            rawMedianRsrp = rawMedian,
            validatedMedianRsrp = validatedMedian,
            excludedSampleCount = suspectCount + missingOrInvalidCount,
            exclusionReasons = exclusionReasons,
            calibrationStatus = "No laboratory calibration offset applied — raw hardware measurements preserved",
            auditExplanation = explanation
        )
    }

    private fun computeRsrpDistribution(values: List<Double>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution("RSRP", "dBm", null, null, null, null, emptyList())
        }
        val total = values.size.toDouble()
        val bExcellent = values.count { it >= -80.0 }
        val bGood = values.count { it >= -90.0 && it < -80.0 }
        val bFair = values.count { it >= -100.0 && it < -90.0 }
        val bPoor = values.count { it >= -110.0 && it < -100.0 }
        val bVeryPoor = values.count { it < -110.0 }

        val buckets = listOf(
            HistogramBucket("≥ -80 dBm (Excellent)", bExcellent, (bExcellent / total) * 100.0),
            HistogramBucket("-80 to -90 dBm (Good)", bGood, (bGood / total) * 100.0),
            HistogramBucket("-90 to -100 dBm (Fair)", bFair, (bFair / total) * 100.0),
            HistogramBucket("-100 to -110 dBm (Poor)", bPoor, (bPoor / total) * 100.0),
            HistogramBucket("< -110 dBm (Very Poor)", bVeryPoor, (bVeryPoor / total) * 100.0)
        )

        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val mean = values.average()

        return MetricDistribution(
            name = "RSRP",
            unit = "dBm",
            min = sorted.first(),
            max = sorted.last(),
            mean = (mean * 10.0).roundToInt() / 10.0,
            median = (median * 10.0).roundToInt() / 10.0,
            buckets = buckets
        )
    }

    private fun computeRsrqDistribution(values: List<Double>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution("RSRQ", "dB", null, null, null, null, emptyList())
        }
        val total = values.size.toDouble()
        val bExcellent = values.count { it >= -10.0 }
        val bGood = values.count { it >= -15.0 && it < -10.0 }
        val bFair = values.count { it >= -19.0 && it < -15.0 }
        val bPoor = values.count { it < -19.0 }

        val buckets = listOf(
            HistogramBucket("≥ -10 dB (Excellent)", bExcellent, (bExcellent / total) * 100.0),
            HistogramBucket("-10 to -15 dB (Good)", bGood, (bGood / total) * 100.0),
            HistogramBucket("-15 to -19 dB (Fair)", bFair, (bFair / total) * 100.0),
            HistogramBucket("< -19 dB (Poor)", bPoor, (bPoor / total) * 100.0)
        )

        val sorted = values.sorted()
        return MetricDistribution(
            name = "RSRQ",
            unit = "dB",
            min = sorted.first(),
            max = sorted.last(),
            mean = (values.average() * 10.0).roundToInt() / 10.0,
            median = sorted[sorted.size / 2],
            buckets = buckets
        )
    }

    private fun computeSinrDistribution(values: List<Double>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution("SINR", "dB", null, null, null, null, emptyList())
        }
        val total = values.size.toDouble()
        val bExcellent = values.count { it >= 15.0 }
        val bGood = values.count { it >= 5.0 && it < 15.0 }
        val bFair = values.count { it >= 0.0 && it < 5.0 }
        val bPoor = values.count { it < 0.0 }

        val buckets = listOf(
            HistogramBucket("≥ 15 dB (Clean)", bExcellent, (bExcellent / total) * 100.0),
            HistogramBucket("5 to 15 dB (Normal)", bGood, (bGood / total) * 100.0),
            HistogramBucket("0 to 5 dB (Noisy)", bFair, (bFair / total) * 100.0),
            HistogramBucket("< 0 dB (Interference)", bPoor, (bPoor / total) * 100.0)
        )

        val sorted = values.sorted()
        return MetricDistribution(
            name = "SINR",
            unit = "dB",
            min = sorted.first(),
            max = sorted.last(),
            mean = (values.average() * 10.0).roundToInt() / 10.0,
            median = sorted[sorted.size / 2],
            buckets = buckets
        )
    }

    private fun computeRssiDistribution(name: String, values: List<Double>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution(name, "dBm", null, null, null, null, emptyList())
        }
        val total = values.size.toDouble()
        val bExcellent = values.count { it >= -70.0 }
        val bGood = values.count { it >= -85.0 && it < -70.0 }
        val bFair = values.count { it >= -100.0 && it < -85.0 }
        val bPoor = values.count { it < -100.0 }

        val buckets = listOf(
            HistogramBucket("≥ -70 dBm (Strong)", bExcellent, (bExcellent / total) * 100.0),
            HistogramBucket("-70 to -85 dBm (Good)", bGood, (bGood / total) * 100.0),
            HistogramBucket("-85 to -100 dBm (Fair)", bFair, (bFair / total) * 100.0),
            HistogramBucket("< -100 dBm (Poor)", bPoor, (bPoor / total) * 100.0)
        )

        val sorted = values.sorted()
        return MetricDistribution(
            name = name,
            unit = "dBm",
            min = sorted.first(),
            max = sorted.last(),
            mean = (values.average() * 10.0).roundToInt() / 10.0,
            median = (sorted[sorted.size / 2] * 10.0).roundToInt() / 10.0,
            buckets = buckets
        )
    }

    private fun computeRscpDistribution(name: String, values: List<Double>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution(name, "dBm", null, null, null, null, emptyList())
        }
        val total = values.size.toDouble()
        val bExcellent = values.count { it >= -75.0 }
        val bGood = values.count { it >= -85.0 && it < -75.0 }
        val bFair = values.count { it >= -95.0 && it < -85.0 }
        val bPoor = values.count { it < -95.0 }

        val buckets = listOf(
            HistogramBucket("≥ -75 dBm (Excellent)", bExcellent, (bExcellent / total) * 100.0),
            HistogramBucket("-75 to -85 dBm (Good)", bGood, (bGood / total) * 100.0),
            HistogramBucket("-85 to -95 dBm (Fair)", bFair, (bFair / total) * 100.0),
            HistogramBucket("< -95 dBm (Poor)", bPoor, (bPoor / total) * 100.0)
        )

        val sorted = values.sorted()
        return MetricDistribution(
            name = name,
            unit = "dBm",
            min = sorted.first(),
            max = sorted.last(),
            mean = (values.average() * 10.0).roundToInt() / 10.0,
            median = (sorted[sorted.size / 2] * 10.0).roundToInt() / 10.0,
            buckets = buckets
        )
    }

    data class NeighborRecord(
        val pci: Int?,
        val rsrpDbm: Double?,
        val rsrqDb: Double?,
        val earfcn: Int?
    )

    private fun computeNeighborDelta(measurements: List<LteMeasurementEntity>): NeighborDeltaSummary {
        var evaluatedCount = 0
        val deltas = mutableListOf<Double>()
        var nearHandover = 0
        var overdueHandover = 0
        var pilotPollution = 0

        for (m in measurements) {
            val servingRsrp = m.rsrpDbm?.toDouble() ?: continue
            val neighbors = parseNeighborRecords(m.neighborCellsJson)

            if (neighbors.isNotEmpty()) {
                // Deduplicate by PCI keeping strongest RSRP for each physical cell
                val deduplicated = neighbors.filter { it.rsrpDbm != null }
                    .groupBy { it.pci ?: -1 }
                    .map { (_, list) -> list.maxByOrNull { it.rsrpDbm!! }!! }

                if (deduplicated.isNotEmpty()) {
                    evaluatedCount++
                    val bestNeighbor = deduplicated.maxByOrNull { it.rsrpDbm!! }!!
                    val delta = servingRsrp - bestNeighbor.rsrpDbm!!
                    deltas.add(delta)

                    if (delta in 0.0..3.0) {
                        nearHandover++
                    } else if (delta < 0.0) {
                        overdueHandover++
                    }

                    // True LTE Pilot Pollution Criteria:
                    // 1. MUST be Intra-frequency (same carrier EARFCN). Inter-frequency neighbors do not collide reference signals!
                    // 2. >= 3 distinct physical cells within 5 dB of serving cell: abs(servingRsrp - it.rsrpDbm) <= 5.0
                    // 3. Lack of dominant server: servingRsrp < -95 dBm OR RSRQ < -14 dB OR SINR < 0.0 dB
                    val intraFreqNeighbors = deduplicated.filter {
                        it.earfcn == null || m.earfcn == null || it.earfcn == m.earfcn
                    }

                    val closeCompetitors = intraFreqNeighbors.filter {
                        kotlin.math.abs(servingRsrp - it.rsrpDbm!!) <= 5.0
                    }

                    val lacksDominance = servingRsrp < -95.0 ||
                        (m.rsrqDb != null && m.rsrqDb < -14) ||
                        (m.sinrDb != null && m.sinrDb < 0.0)

                    if (closeCompetitors.size >= 3 && lacksDominance) {
                        pilotPollution++
                    }
                }
            }
        }

        val avgDelta = if (deltas.isNotEmpty()) (deltas.average() * 10.0).roundToInt() / 10.0 else null

        return NeighborDeltaSummary(
            totalEvaluatedSamples = evaluatedCount,
            avgDeltaRsrpDb = avgDelta,
            nearHandoverMarginCount = nearHandover,
            overdueHandoverCount = overdueHandover,
            pilotPollutionCount = pilotPollution
        )
    }

    private fun computeInterference(
        measurements: List<LteMeasurementEntity>,
        deltaSummary: NeighborDeltaSummary
    ): DownlinkInterferenceEstimate {
        val validSamples = measurements.filter { it.rsrpDbm != null && it.rsrqDb != null }
        if (validSamples.size < 10) {
            return DownlinkInterferenceEstimate(
                avgEstimatedInterferenceDbm = null,
                highInterferenceRatioPct = 0.0,
                pilotPollutionRatioPct = 0.0,
                interpretationSummary = if (validSamples.isEmpty()) {
                    "Insufficient RSRP/RSRQ samples (0 valid samples)."
                } else {
                    "Insufficient evidence: Fewer than 10 valid telemetry samples available (${validSamples.size}/10)."
                }
            )
        }

        // In LTE: RSRQ = N * (RSRP / RSSI).
        // A high RSRP (> -95 dBm) coupled with low RSRQ (< -15 dB) is the primary indicator of co-channel interference!
        val highInterferenceSamples = validSamples.count {
            val rsrp = it.rsrpDbm ?: -140
            val rsrq = it.rsrqDb ?: -30
            rsrp >= -95 && rsrq < -15
        }

        val highInterferencePct = (highInterferenceSamples.toDouble() / validSamples.size.toDouble()) * 100.0
        val pilotPollutionPct = if (deltaSummary.totalEvaluatedSamples > 0) {
            (deltaSummary.pilotPollutionCount.toDouble() / deltaSummary.totalEvaluatedSamples.toDouble()) * 100.0
        } else 0.0

        val avgInterferenceDbm = validSamples.mapNotNull {
            val rsrp = it.rsrpDbm?.toDouble() ?: return@mapNotNull null
            val rsrq = it.rsrqDb?.toDouble() ?: return@mapNotNull null
            // Approximate relative interference indicator: (RSRP - RSRQ)
            (rsrp - rsrq)
        }.average().takeIf { !it.isNaN() }

        val sinrValues = validSamples.mapNotNull { it.sinrDb }
        val hasSinr = sinrValues.isNotEmpty()
        val hasNeighbors = deltaSummary.totalEvaluatedSamples > 0

        val interpretation = when {
            !hasSinr && !hasNeighbors -> {
                "Interference status inconclusive — device telemetry does not expose physical SINR/CINR or neighbor cell metrics."
            }
            !hasSinr -> {
                when {
                    pilotPollutionPct > 15.0 ->
                        "Potential interference concern: High pilot pollution (${String.format(Locale.US, "%.1f", pilotPollutionPct)}%) observed across intra-frequency neighbors without physical SINR exposed."
                    highInterferencePct > 25.0 ->
                        "Potential interference concern: ${String.format(Locale.US, "%.1f", highInterferencePct)}% samples exhibit high RSRP with degraded RSRQ (SINR unexposed)."
                    else ->
                        "Interference status inconclusive — device telemetry does not expose physical SINR/CINR."
                }
            }
            else -> {
                val meanSinr = sinrValues.average()
                when {
                    meanSinr < 0.0 || highInterferencePct > 25.0 ->
                        "Measured impairment: Low physical SINR (mean ${String.format(Locale.US, "%.1f", meanSinr)} dB) indicates severe co-channel interference or pilot pollution."
                    pilotPollutionPct > 15.0 ->
                        "Potential interference concern: High pilot pollution (${String.format(Locale.US, "%.1f", pilotPollutionPct)}%) despite mean SINR of ${String.format(Locale.US, "%.1f", meanSinr)} dB."
                    deltaSummary.overdueHandoverCount > 5 ->
                        "Handover latency observed: Target neighbor exceeds serving cell before handover occurs."
                    meanSinr > 10.0 && highInterferencePct <= 10.0 && pilotPollutionPct == 0.0 ->
                        "Measured radio channel quality is good (Mean SINR: ${String.format(Locale.US, "%.1f", meanSinr)} dB). Minimal co-channel interference observed."
                    else ->
                        "Moderate radio conditions: Mean SINR ${String.format(Locale.US, "%.1f", meanSinr)} dB, low co-channel contention."
                }
            }
        }

        return DownlinkInterferenceEstimate(
            avgEstimatedInterferenceDbm = avgInterferenceDbm?.let { (it * 10.0).roundToInt() / 10.0 },
            highInterferenceRatioPct = (highInterferencePct * 10.0).roundToInt() / 10.0,
            pilotPollutionRatioPct = (pilotPollutionPct * 10.0).roundToInt() / 10.0,
            interpretationSummary = interpretation
        )
    }

    private fun parseNeighborRecords(raw: String?): List<NeighborRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = mutableListOf<NeighborRecord>()
        val cells = raw.split(";")
        for (c in cells) {
            if (c.isBlank()) continue
            if (c.contains("RSRP:")) {
                // Key-value format e.g. "PCI:123/RSRP:-90/RSRQ:-12/EARFCN:1399"
                val tokens = c.split("/")
                var pci: Int? = null
                var rsrp: Double? = null
                var rsrq: Double? = null
                var earfcn: Int? = null
                for (t in tokens) {
                    when {
                        t.startsWith("PCI:") -> pci = t.substringAfter("PCI:").toIntOrNull()
                        t.startsWith("RSRP:") -> rsrp = t.substringAfter("RSRP:").toDoubleOrNull()
                        t.startsWith("RSRQ:") -> rsrq = t.substringAfter("RSRQ:").toDoubleOrNull()
                        t.startsWith("EARFCN:") -> earfcn = t.substringAfter("EARFCN:").toIntOrNull()
                    }
                }
                if (rsrp != null) {
                    list.add(NeighborRecord(pci, rsrp, rsrq, earfcn))
                }
            } else {
                // Standard colon format: "PCI:RSRP:RSRQ:EARFCN"
                val parts = c.split(":")
                val pci = parts.getOrNull(0)?.toIntOrNull()
                val rsrp = parts.getOrNull(1)?.toDoubleOrNull()
                val rsrq = parts.getOrNull(2)?.toDoubleOrNull()
                val earfcn = parts.getOrNull(3)?.toIntOrNull()
                if (rsrp != null) {
                    list.add(NeighborRecord(pci, rsrp, rsrq, earfcn))
                }
            }
        }
        return list
    }
}
