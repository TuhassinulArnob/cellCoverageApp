package com.cellscope.drive.analytics

import com.cellscope.drive.data.entity.LteMeasurementEntity
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.telephony.CellularBandResolver
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class InterferenceClassification(val label: String) {
    OBSERVED_SAME_CHANNEL_CELLS("Observed Same-Channel Cells"),
    POTENTIAL_PCI_REUSE_CONCERN("Potential PCI Reuse Concern"),
    POSSIBLE_INTERFERENCE_RISK("Possible Interference Risk — Requires Further Validation"),
    INSUFFICIENT_DATA("Insufficient Data")
}

data class CoChannelPair(
    val earfcnOrArfcn: Int,
    val pciA: Int,
    val pciB: Int,
    val distanceM: Double?,
    val isSimultaneousObservation: Boolean,
    val crsMod3Collision: Boolean,
    val mod30Collision: Boolean,
    val classification: InterferenceClassification,
    val engineeringEvidence: String
)

data class AdjacentChannelPair(
    val channelA: Int,
    val channelB: Int,
    val centerFreqA: Double,
    val centerFreqB: Double,
    val deltaFreqMhz: Double,
    val bandwidthA: Double,
    val bandwidthB: Double,
    val overlapsNominalPassband: Boolean,
    val classification: InterferenceClassification,
    val engineeringEvidence: String
)

data class InterferenceReport(
    val totalCoChannelPairs: Int,
    val totalAdjacentPairs: Int,
    val pciReuseConcernsCount: Int,
    val crsCollisionsCount: Int,
    val coChannelEvents: List<CoChannelPair>,
    val adjacentEvents: List<AdjacentChannelPair>,
    val radioQualityCorrelationSummary: String,
    val analyticalDisclaimer: String
)

object InterferenceAnalyzer {

    const val ANALYTICAL_DISCLAIMER =
        "Note: Radio interference analysis correlates upper-layer Android TelephonyManager cell observations (carrier frequency and nominal bandwidth). Standard modems do not expose raw ACLR, spectral leakage, or RF emissions masks. Classifications indicate allocation geometry and co-channel proximity hypotheses rather than direct baseband spectrum measurements."

    fun analyze(
        measurements: List<LteMeasurementEntity>,
        activeCells: List<ObservedCell> = emptyList()
    ): InterferenceReport {
        val coChannelList = mutableListOf<CoChannelPair>()
        val adjacentList = mutableListOf<AdjacentChannelPair>()

        // 1a. Co-Channel Analysis from Live Active Cells
        val validActive = activeCells.filter { it.pci != null && (it.earfcn != null || it.nrArfcn != null) }
        val activeByChannel = validActive.groupBy { it.earfcn ?: it.nrArfcn!! }
        for ((channel, cells) in activeByChannel) {
            if (cells.size > 1) {
                for (i in cells.indices) {
                    for (j in i + 1 until cells.size) {
                        val cA = cells[i]
                        val cB = cells[j]
                        val pciA = cA.pci!!
                        val pciB = cB.pci!!
                        val isSamePci = pciA == pciB
                        val crsMod3 = (pciA % 3) == (pciB % 3)
                        val mod30 = (pciA % 30) == (pciB % 30)

                        val classification = when {
                            isSamePci -> InterferenceClassification.POTENTIAL_PCI_REUSE_CONCERN
                            crsMod3 -> InterferenceClassification.POSSIBLE_INTERFERENCE_RISK
                            else -> InterferenceClassification.OBSERVED_SAME_CHANNEL_CELLS
                        }

                        val evidence = StringBuilder()
                        if (isSamePci) {
                            evidence.append("Identical PCI Collision: PCI $pciA observed simultaneously on channel $channel. Severe pilot and control channel corruption hazard.")
                        } else if (crsMod3) {
                            evidence.append("CRS Modulo-3 Collision: PCI $pciA and PCI $pciB share subcarrier shift (${pciA % 3}) on channel $channel. Reference signals collide in frequency grid.")
                        } else {
                            evidence.append("Simultaneous co-channel observation on channel $channel (PCI $pciA and PCI $pciB).")
                        }

                        coChannelList.add(
                            CoChannelPair(
                                earfcnOrArfcn = channel,
                                pciA = pciA,
                                pciB = pciB,
                                distanceM = null,
                                isSimultaneousObservation = true,
                                crsMod3Collision = crsMod3,
                                mod30Collision = mod30,
                                classification = classification,
                                engineeringEvidence = evidence.toString()
                            )
                        )
                    }
                }
            }
        }

        // 1b. Co-Channel Analysis from Historical Measurements
        val validHistorical = measurements.filter { it.servingPci != null && (it.earfcn != null || it.nrArfcn != null) }
        
        // Group by carrier channel
        val byChannel = validHistorical.groupBy { it.earfcn ?: it.nrArfcn!! }

        for ((channel, samples) in byChannel) {
            // Group samples by PCI
            val pciGroups = samples.groupBy { it.servingPci!! }
            val pcis = pciGroups.keys.toList()

            // Check intra-PCI spatial reuse (same PCI observed across different coordinates)
            for (pci in pcis) {
                val pciSamples = pciGroups[pci] ?: continue
                val validCoords = pciSamples.filter { it.latitude != null && it.longitude != null }
                if (validCoords.size > 1) {
                    val firstLoc = validCoords.first()
                    val maxDistSample = validCoords.maxByOrNull {
                        calculateHaversineM(firstLoc.latitude!!, firstLoc.longitude!!, it.latitude!!, it.longitude!!)
                    }
                    if (maxDistSample != null) {
                        val distM = calculateHaversineM(firstLoc.latitude!!, firstLoc.longitude!!, maxDistSample.latitude!!, maxDistSample.longitude!!)
                        if (distM > 100.0) {
                            val classification = if (distM < 2000.0) {
                                InterferenceClassification.POTENTIAL_PCI_REUSE_CONCERN
                            } else {
                                InterferenceClassification.OBSERVED_SAME_CHANNEL_CELLS
                            }
                            val evidence = if (distM < 2000.0) {
                                "Identical PCI $pci reused on channel $channel with spatial separation of ${distM.toInt()}m (<2000m). Potential uncoordinated PCI collision hazard."
                            } else {
                                "Planned PCI $pci reuse on channel $channel with healthy spatial separation of ${String.format(Locale.US, "%.1f km", distM / 1000.0)}."
                            }
                            coChannelList.add(
                                CoChannelPair(
                                    earfcnOrArfcn = channel,
                                    pciA = pci,
                                    pciB = pci,
                                    distanceM = distM,
                                    isSimultaneousObservation = false,
                                    crsMod3Collision = true,
                                    mod30Collision = true,
                                    classification = classification,
                                    engineeringEvidence = evidence
                                )
                            )
                        }
                    }
                }
            }

            // Check inter-PCI co-channel pairs
            for (i in pcis.indices) {
                for (j in i + 1 until pcis.size) {
                    val pciA = pcis[i]
                    val pciB = pcis[j]

                    val samplesA = pciGroups[pciA] ?: emptyList()
                    val samplesB = pciGroups[pciB] ?: emptyList()

                    // Compute geographic separation
                    val bestA = samplesA.firstOrNull { it.latitude != null && it.longitude != null }
                    val bestB = samplesB.firstOrNull { it.latitude != null && it.longitude != null }

                    val distM = if (bestA?.latitude != null && bestA.longitude != null && bestB?.latitude != null && bestB.longitude != null) {
                        calculateHaversineM(bestA.latitude, bestA.longitude, bestB.latitude, bestB.longitude)
                    } else null

                    // Check for simultaneous or time-coincident observation (within 30 seconds)
                    val isSimultaneous = samplesA.any { a ->
                        samplesB.any { b -> kotlin.math.abs(a.timestampMs - b.timestampMs) < 30000L }
                    }

                    val crsMod3 = (pciA % 3) == (pciB % 3)
                    val mod30 = (pciA % 30) == (pciB % 30)

                    val classification = when {
                        crsMod3 && isSimultaneous ->
                            InterferenceClassification.POSSIBLE_INTERFERENCE_RISK
                        distM != null && distM < 1000.0 ->
                            InterferenceClassification.POSSIBLE_INTERFERENCE_RISK
                        distM != null && distM >= 2000.0 ->
                            InterferenceClassification.OBSERVED_SAME_CHANNEL_CELLS
                        else ->
                            InterferenceClassification.INSUFFICIENT_DATA
                    }

                    val evidence = StringBuilder()
                    if (crsMod3) {
                        evidence.append("CRS Modulo-3 Collision: PCI $pciA and PCI $pciB share CRS modulo-3 (${pciA % 3}) on channel $channel. ")
                        evidence.append("Downlink Cell-Specific Reference Signals overlap in subcarrier frequency grid.")
                    } else {
                        evidence.append("Standard co-channel cellular deployment on channel $channel (PCIs: $pciA, $pciB). ")
                        if (distM != null) evidence.append("Measured separation: ${String.format(Locale.US, "%.1f km", distM / 1000.0)}.")
                    }

                    coChannelList.add(
                        CoChannelPair(
                            earfcnOrArfcn = channel,
                            pciA = pciA,
                            pciB = pciB,
                            distanceM = distM,
                            isSimultaneousObservation = isSimultaneous,
                            crsMod3Collision = crsMod3,
                            mod30Collision = mod30,
                            classification = classification,
                            engineeringEvidence = evidence.toString()
                        )
                    )
                }
            }
        }

        // 2. Adjacent-Channel Carrier Passband Analysis
        val activeCarriers = activeCells.mapNotNull { cell ->
            CellularBandResolver.resolve(cell)
        }.distinctBy { it.channelNumber }

        for (i in activeCarriers.indices) {
            for (j in i + 1 until activeCarriers.size) {
                val c1 = activeCarriers[i]
                val c2 = activeCarriers[j]

                val isNominal = c1.isNominalBandwidth || c2.isNominalBandwidth
                val deltaF = kotlin.math.abs(c1.dlCenterFreqMhz - c2.dlCenterFreqMhz)
                val combinedBwHalf = (c1.channelBandwidthMhz + c2.channelBandwidthMhz) / 2.0

                val isAdjacent = deltaF < (combinedBwHalf + 5.0)
                val overlaps = deltaF < combinedBwHalf

                if (isAdjacent) {
                    val classification = when {
                        isNominal -> InterferenceClassification.INSUFFICIENT_DATA
                        overlaps -> InterferenceClassification.POSSIBLE_INTERFERENCE_RISK
                        else -> InterferenceClassification.OBSERVED_SAME_CHANNEL_CELLS
                    }

                    val evidence = if (isNominal) {
                        String.format(
                            Locale.US,
                            "Insufficient data to assess channel overlap (bandwidth not exposed by modem RIL): %s (%.1f MHz) and %s (%.1f MHz) have carrier spacing of %.1f MHz.",
                            c1.bandName, c1.dlCenterFreqMhz, c2.bandName, c2.dlCenterFreqMhz, deltaF
                        )
                    } else if (overlaps) {
                        String.format(
                            Locale.US,
                            "Nominal channel overlap (Overlapping Passbands): %s (%.1f MHz, BW %.1f MHz) and %s (%.1f MHz, BW %.1f MHz) have carrier center spacing of %.1f MHz (required: %.1f MHz). Potential passband contention.",
                            c1.bandName, c1.dlCenterFreqMhz, c1.channelBandwidthMhz,
                            c2.bandName, c2.dlCenterFreqMhz, c2.channelBandwidthMhz,
                            deltaF, combinedBwHalf
                        )
                    } else {
                        String.format(
                            Locale.US,
                            "Adjacent carrier assignment: %s (%.1f MHz) and %s (%.1f MHz) have channel edge spacing of %.1f MHz. Normal adjacent operator deployment.",
                            c1.bandName, c1.dlCenterFreqMhz, c2.bandName, c2.dlCenterFreqMhz,
                            deltaF - combinedBwHalf
                        )
                    }

                    adjacentList.add(
                        AdjacentChannelPair(
                            channelA = c1.channelNumber,
                            channelB = c2.channelNumber,
                            centerFreqA = c1.dlCenterFreqMhz,
                            centerFreqB = c2.dlCenterFreqMhz,
                            deltaFreqMhz = deltaF,
                            bandwidthA = c1.channelBandwidthMhz,
                            bandwidthB = c2.channelBandwidthMhz,
                            overlapsNominalPassband = overlaps && !isNominal,
                            classification = classification,
                            engineeringEvidence = evidence
                        )
                    )
                }
            }
        }

        // 3. Radio Quality Correlation
        val meanSinr = measurements.mapNotNull { it.sinrDb }.average().takeIf { !it.isNaN() }
        val meanRsrq = measurements.mapNotNull { it.rsrqDb }.average().takeIf { !it.isNaN() }
        val pilotConcerns = coChannelList.count { it.crsMod3Collision || it.classification == InterferenceClassification.POSSIBLE_INTERFERENCE_RISK }

        val qualitySb = StringBuilder()
        if (measurements.size < 10) {
            qualitySb.append("Insufficient evidence: Survey contains ${measurements.size} sample(s). At least 10 samples required for reliable RF correlation.")
        } else if (pilotConcerns > 0) {
            qualitySb.append("Identified $pilotConcerns potential co-channel/CRS subcarrier contention pair(s). ")
            if (meanSinr != null) {
                qualitySb.append(String.format(Locale.US, "Route mean SINR: %.1f dB (RSRQ: %.1f dB). ", meanSinr, meanRsrq ?: 0.0))
                if (meanSinr < 5.0) {
                    qualitySb.append("Low average SINR correlates with multi-sector co-channel overlap.")
                } else {
                    qualitySb.append("Moderate/high SINR indicates sufficient dominant-server path-loss margin along measured points.")
                }
            } else {
                qualitySb.append("SINR metric not exposed by device modem; verification requires field scanner or network trace.")
            }
        } else {
            if (meanSinr != null && meanSinr >= 10.0) {
                qualitySb.append(String.format(Locale.US, "Co-channel frequency reuse exhibits healthy spatial separation (Mean SINR: %.1f dB). Zero CRS subcarrier collisions detected.", meanSinr))
            } else if (meanSinr != null) {
                qualitySb.append(String.format(Locale.US, "Co-channel frequency reuse exhibits healthy spatial separation. Mean SINR: %.1f dB.", meanSinr))
            } else {
                qualitySb.append("Co-channel frequency reuse exhibits healthy spatial separation. Note: Physical SINR is not exposed by modem RIL; channel interference cannot be conclusively verified from telemetry alone.")
            }
        }

        return InterferenceReport(
            totalCoChannelPairs = coChannelList.size,
            totalAdjacentPairs = adjacentList.size,
            pciReuseConcernsCount = coChannelList.count { it.classification == InterferenceClassification.POTENTIAL_PCI_REUSE_CONCERN },
            crsCollisionsCount = coChannelList.count { it.crsMod3Collision },
            coChannelEvents = coChannelList,
            adjacentEvents = adjacentList,
            radioQualityCorrelationSummary = qualitySb.toString(),
            analyticalDisclaimer = ANALYTICAL_DISCLAIMER
        )
    }

    private fun calculateHaversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
