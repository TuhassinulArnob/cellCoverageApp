package com.cellscope.drive.telephony

import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus

data class RawCellCandidate(
    val pci: Int?,
    val tac: Int? = null,
    val eci: Int? = null,
    val earfcn: Int? = null,
    val bandwidthKhz: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val rssiDbm: Int? = null,
    val sinrDb: Double? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val isRegistered: Boolean = false,
    val connectionStatus: Int = CONNECTION_UNKNOWN,
    val networkType: String = "LTE",
    val operatorAlpha: String? = null,
    val operatorNumeric: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val nci: Long? = null,
    val nrArfcn: Int? = null,
    val csiRsrpDbm: Int? = null,
    val csiRsrqDb: Int? = null,
    val csiSinrDb: Double? = null,
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
) {
    companion object {
        const val CONNECTION_NONE = 0
        const val CONNECTION_PRIMARY_SERVING = 1
        const val CONNECTION_SECONDARY_SERVING = 2
        const val CONNECTION_UNKNOWN = Integer.MAX_VALUE
    }
}

object ServingCellClassifier {

    /**
     * Authoritatively resolves serving status for a set of simultaneously scanned cells.
     *
     * In Dual-SIM environments where Android modem reports multiple registered cells (one per SIM),
     * [preferredPlmn] (the active data subscription's registered PLMN) is authoritatively prioritized
     * to prevent an idle or standby SIM with a stronger signal from overriding the active data SIM's PCell.
     *
     * Enforces:
     * 1. At most one Primary Serving Cell (PCell).
     * 2. Secondary Serving Cells (SCells) identified via Carrier Aggregation.
     * 3. Ambiguous registered cells demoted to SERVING_UNCONFIRMED, never duplicate PCells.
     * 4. Zero registered cells never promotes a neighbor.
     */
    fun classify(candidates: List<RawCellCandidate>, preferredPlmn: String? = null): List<ObservedCell> {
        if (candidates.isEmpty()) return emptyList()

        val cleanPreferredPlmn = preferredPlmn?.trim()?.replace("-", "")

        // Pass 1: Check for explicit Android API 28+ CellConnectionStatus
        val explicitPrimaries = candidates.filter { it.connectionStatus == RawCellCandidate.CONNECTION_PRIMARY_SERVING }

        // If Android API 28+ provided unambiguous PRIMARY_SERVING
        if (explicitPrimaries.isNotEmpty()) {
            val primaryCandidate = explicitPrimaries.maxWithOrNull(
                compareBy<RawCellCandidate> { cleanPreferredPlmn != null && it.operatorNumeric?.replace("-", "") == cleanPreferredPlmn }
                    .thenBy { hasFullIdentity(it) }
                    .thenBy { it.rsrpDbm ?: -999 }
            ) ?: explicitPrimaries.first()

            return candidates.map { c ->
                when {
                    c === primaryCandidate -> c.toObservedCell(
                        ServingStatus.PRIMARY_SERVING,
                        "API 28+ CellConnectionStatus: Primary Serving (PCell)"
                    )
                    c.connectionStatus == RawCellCandidate.CONNECTION_SECONDARY_SERVING -> c.toObservedCell(
                        ServingStatus.SECONDARY_SERVING,
                        "API 28+ CellConnectionStatus: Secondary Serving (Carrier Aggregation)"
                    )
                    explicitPrimaries.contains(c) -> c.toObservedCell(
                        ServingStatus.SERVING_UNCONFIRMED,
                        "Observed cell — serving status unconfirmed (Multiple Primary Serving flags reported by RIL)"
                    )
                    c.isRegistered -> c.toObservedCell(
                        ServingStatus.SERVING_UNCONFIRMED,
                        "Observed cell — serving status unconfirmed (Registered on alternate SIM/bearer)"
                    )
                    else -> c.toObservedCell(
                        ServingStatus.NEIGHBOR,
                        "API 28+ Neighbor Cell"
                    )
                }
            }
        }

        // Pass 2: Fall back to isRegistered flag (API < 28 or CONNECTION_UNKNOWN)
        val registeredCandidates = candidates.filter { it.isRegistered }

        // If no cell is registered, DO NOT promote any neighbor!
        if (registeredCandidates.isEmpty()) {
            return candidates.map { c ->
                if (c.connectionStatus == RawCellCandidate.CONNECTION_SECONDARY_SERVING) {
                    c.toObservedCell(
                        ServingStatus.SECONDARY_SERVING,
                        "API 28+ CellConnectionStatus: Secondary Serving (Carrier Aggregation)"
                    )
                } else {
                    c.toObservedCell(
                        ServingStatus.NEIGHBOR,
                        "Unregistered cell (Neighbor)"
                    )
                }
            }
        }

        // Exactly one registered cell: authoritative PCell
        if (registeredCandidates.size == 1) {
            val primary = registeredCandidates.first()
            return candidates.map { c ->
                when {
                    c === primary -> c.toObservedCell(
                        ServingStatus.PRIMARY_SERVING,
                        "Modem registered serving cell (PCell)"
                    )
                    c.connectionStatus == RawCellCandidate.CONNECTION_SECONDARY_SERVING -> c.toObservedCell(
                        ServingStatus.SECONDARY_SERVING,
                        "API 28+ Secondary Serving (Carrier Aggregation)"
                    )
                    else -> c.toObservedCell(
                        ServingStatus.NEIGHBOR,
                        "Unregistered cell (Neighbor)"
                    )
                }
            }
        }

        // Multiple registered cells reported by RIL (e.g. Dual-SIM device): Disambiguate!
        // First prioritize candidates that match the active data subscription's registered PLMN
        val matchingPlmnCandidates = if (!cleanPreferredPlmn.isNullOrBlank()) {
            registeredCandidates.filter { it.operatorNumeric?.replace("-", "") == cleanPreferredPlmn }
        } else emptyList()

        val candidatePool = if (matchingPlmnCandidates.isNotEmpty()) matchingPlmnCandidates else registeredCandidates

        // Select exactly one PCell from eligible pool based on complete identity (TAC/ECI) and superior RSRP
        val primaryCandidate = candidatePool.maxWithOrNull(
            compareBy<RawCellCandidate> { hasFullIdentity(it) }
                .thenBy { it.rsrpDbm ?: -999 }
                .thenBy { (it.earfcn ?: it.nrArfcn) != null }
        ) ?: candidatePool.first()

        return candidates.map { c ->
            when {
                c === primaryCandidate -> c.toObservedCell(
                    ServingStatus.PRIMARY_SERVING,
                    if (matchingPlmnCandidates.isNotEmpty()) {
                        "Primary serving cell verified for active data SIM (${c.operatorAlpha ?: cleanPreferredPlmn})"
                    } else {
                        "Primary serving cell resolved from multiple registered cells (complete identity and superior signal)"
                    }
                )
                c.connectionStatus == RawCellCandidate.CONNECTION_SECONDARY_SERVING -> c.toObservedCell(
                    ServingStatus.SECONDARY_SERVING,
                    "Carrier Aggregation Secondary Cell (SCell)"
                )
                c.isRegistered -> c.toObservedCell(
                    ServingStatus.SERVING_UNCONFIRMED,
                    if (cleanPreferredPlmn != null && c.operatorNumeric?.replace("-", "") != cleanPreferredPlmn) {
                        "Observed registered cell on alternate SIM subscription (${c.operatorAlpha ?: c.operatorNumeric ?: "Secondary SIM"})"
                    } else {
                        "Observed cell — serving status unconfirmed (RIL reported multiple registered cells)"
                    }
                )
                else -> c.toObservedCell(
                    ServingStatus.NEIGHBOR,
                    "Unregistered cell (Neighbor)"
                )
            }
        }
    }

    private fun hasFullIdentity(c: RawCellCandidate): Boolean {
        val hasTac = (c.tac != null && c.tac > 0)
        val hasCi = (c.eci != null && c.eci > 0) || (c.nci != null && c.nci > 0L)
        return hasTac && hasCi
    }

    private fun RawCellCandidate.toObservedCell(status: ServingStatus, evidence: String): ObservedCell {
        return ObservedCell(
            pci = this.pci,
            tac = this.tac,
            eci = this.eci,
            earfcn = this.earfcn,
            bandwidthKhz = this.bandwidthKhz,
            rsrpDbm = this.rsrpDbm,
            rsrqDb = this.rsrqDb,
            rssiDbm = this.rssiDbm,
            sinrDb = this.sinrDb,
            cqi = this.cqi,
            timingAdvance = this.timingAdvance,
            servingStatus = status,
            isServing = status.isServingConnection,
            servingEvidence = evidence,
            networkType = this.networkType,
            operatorAlpha = this.operatorAlpha,
            operatorNumeric = this.operatorNumeric,
            timestampMs = this.timestampMs,
            nci = this.nci,
            nrArfcn = this.nrArfcn,
            csiRsrpDbm = this.csiRsrpDbm,
            csiRsrqDb = this.csiRsrqDb,
            csiSinrDb = this.csiSinrDb,
            servingRat = this.servingRat,
            channelNumber = this.channelNumber,
            channelType = this.channelType,
            primarySignalDbm = this.primarySignalDbm,
            signalMetricType = this.signalMetricType,
            secondarySignalDb = this.secondarySignalDb,
            sinrSource = this.sinrSource,
            estimationMethod = this.estimationMethod,
            estimationAssumptions = this.estimationAssumptions,
            sinrConfidence = this.sinrConfidence,
            qualitativeDiagnostic = this.qualitativeDiagnostic,
            impliedRssiDbm = this.impliedRssiDbm
        )
    }
}
