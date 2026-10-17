package com.cellscope.drive.analytics

import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.telephony.LteBandTable
import java.util.Locale

data class ServingNeighborAnalysis(
    val hasStrongerNeighbor: Boolean,
    val servingCell: ObservedCell,
    val bestNeighborCell: ObservedCell?,
    val deltaRsrpDb: Double?, // serving.rsrp - neighbor.rsrp (negative if neighbor stronger)
    val isInterFrequency: Boolean,
    val servingBandText: String,
    val neighborBandText: String,
    val engineeringVerdict: String,
    val primaryReason: String,
    val standardsBasis: String,
    val confirmedFacts: List<String>,
    val networkHypotheses: List<String>
)

object ServingNeighborIntelligence {

    fun analyze(
        servingCell: ObservedCell,
        neighborCells: List<ObservedCell>
    ): ServingNeighborAnalysis {
        val validNeighbors = neighborCells.filter { it.rsrpDbm != null }
        val bestNeighbor = validNeighbors.maxByOrNull { it.rsrpDbm!! }

        val servingRsrp = servingCell.rsrpDbm?.toDouble()
        val bestNeighborRsrp = bestNeighbor?.rsrpDbm?.toDouble()

        val servingBand = LteBandTable.resolveEarfcn(servingCell.earfcn)
        val servingBandStr = servingBand?.let { "Band ${it.bandNumber} (${it.designation}, ${it.dlFrequencyMhz} MHz)" }
            ?: (servingCell.earfcn?.let { "EARFCN $it" } ?: "Carrier Unknown")

        if (bestNeighbor == null || servingRsrp == null || bestNeighborRsrp == null) {
            return ServingNeighborAnalysis(
                hasStrongerNeighbor = false,
                servingCell = servingCell,
                bestNeighborCell = bestNeighbor,
                deltaRsrpDb = null,
                isInterFrequency = false,
                servingBandText = servingBandStr,
                neighborBandText = "None Observed",
                engineeringVerdict = "Dominant Serving Cell",
                primaryReason = "No valid neighbor carrier signal is currently detected by modem scan.",
                standardsBasis = "3GPP TS 36.304: Serving cell provides adequate coverage without competition.",
                confirmedFacts = listOf("Serving PCI ${servingCell.pci ?: "—"} at ${servingCell.rsrpDbm ?: "—"} dBm ($servingBandStr)"),
                networkHypotheses = emptyList()
            )
        }

        val delta = servingRsrp - bestNeighborRsrp
        val neighborBand = LteBandTable.resolveEarfcn(bestNeighbor.earfcn)
        val neighborBandStr = neighborBand?.let { "Band ${it.bandNumber} (${it.designation}, ${it.dlFrequencyMhz} MHz)" }
            ?: (bestNeighbor.earfcn?.let { "EARFCN $it" } ?: "Carrier Unknown")

        val isInterFreq = servingCell.earfcn != null && bestNeighbor.earfcn != null && servingCell.earfcn != bestNeighbor.earfcn

        if (delta >= 0.0) {
            // Serving is equal or stronger than neighbor
            return ServingNeighborAnalysis(
                hasStrongerNeighbor = false,
                servingCell = servingCell,
                bestNeighborCell = bestNeighbor,
                deltaRsrpDb = delta,
                isInterFrequency = isInterFreq,
                servingBandText = servingBandStr,
                neighborBandText = neighborBandStr,
                engineeringVerdict = "Proper Server Dominance (+${String.format(Locale.US, "%.1f", delta)} dB)",
                primaryReason = "Serving cell RSRP is stronger than the best neighbor cell.",
                standardsBasis = "3GPP TS 36.304: Normal cell camping. Serving cell dominates radio conditions.",
                confirmedFacts = listOf(
                    "Serving PCI ${servingCell.pci ?: "—"} is ${String.format(Locale.US, "%.1f", delta)} dB stronger than Neighbor PCI ${bestNeighbor.pci ?: "—"}",
                    "Carrier relationship: ${if (isInterFreq) "Inter-frequency" else "Intra-frequency"}"
                ),
                networkHypotheses = emptyList()
            )
        }

        // Neighbor is STRONGER than serving (delta < 0.0)
        val deltaAdvantage = -delta // positive dB difference

        val confirmedFacts = mutableListOf<String>()
        val hypotheses = mutableListOf<String>()

        confirmedFacts.add("Serving PCI ${servingCell.pci ?: "—"} on EARFCN ${servingCell.earfcn ?: "—"} has RSRP ${servingCell.rsrpDbm} dBm.")
        confirmedFacts.add("Neighbor PCI ${bestNeighbor.pci ?: "—"} on EARFCN ${bestNeighbor.earfcn ?: "—"} has RSRP ${bestNeighbor.rsrpDbm} dBm.")
        confirmedFacts.add("Neighbor is ${String.format(Locale.US, "%.1f", deltaAdvantage)} dB stronger than serving cell.")

        if (isInterFreq) {
            confirmedFacts.add("Frequency relationship: INTER-FREQUENCY (Serving $servingBandStr vs Neighbor $neighborBandStr).")
            hypotheses.add("3GPP Carrier Reselection Priority (SIB5 cellReselectionPriority): Operators assign higher priority to capacity layers (Band 3 / 1800 MHz) than coverage layers (Band 8 / 900 MHz). As long as serving RSRP exceeds s-NonIntraSearch threshold (typically > -115 dBm), the UE is prohibited from reselecting down to Band 8.")
            hypotheses.add("Connected Mode Event A5 Threshold: Inter-frequency handover triggers Event A5 only when serving falls below Threshold 1 (typically -112 dBm) AND target exceeds Threshold 2. Since serving is currently ${servingCell.rsrpDbm} dBm (above Threshold 1), handover is suppressed to protect cell throughput.")
            hypotheses.add("RF Propagation Path Loss: Lower frequencies (e.g. 900 MHz) experience ~6 dB lower free-space path loss and higher building penetration than 1800 MHz. Higher RSRP on Band 8 is an RF propagation artifact, not indicative of better capacity.")

            return ServingNeighborAnalysis(
                hasStrongerNeighbor = true,
                servingCell = servingCell,
                bestNeighborCell = bestNeighbor,
                deltaRsrpDb = delta,
                isInterFrequency = true,
                servingBandText = servingBandStr,
                neighborBandText = neighborBandStr,
                engineeringVerdict = "Inter-Frequency Carrier Layer Management",
                primaryReason = "Neighbor is on a different carrier layer (${neighborBand?.bandNumber?.let { "Band $it" } ?: "Inter-freq"}). Network deliberately keeps UE on high-capacity layer ($servingBandStr) to preserve bandwidth.",
                standardsBasis = "3GPP TS 36.304 §5.2.4 & TS 36.331 Event A5: Capacity-layer retention policy holds connection until serving degrades below threshold.",
                confirmedFacts = confirmedFacts,
                networkHypotheses = hypotheses
            )
        } else {
            // Intra-frequency competition!
            confirmedFacts.add("Frequency relationship: INTRA-FREQUENCY (Same carrier EARFCN ${servingCell.earfcn}).")
            hypotheses.add("3GPP Event A3 Handover Hysteresis: Intra-frequency handover requires RSRP_neighbor > RSRP_serving + A3_Offset + Hysteresis (typically 2 to 3 dB) sustained over Time-to-Trigger (TTT, typically 320ms to 640ms) to prevent ping-pong effect.")
            hypotheses.add("eNodeB Admission Control / PRB Load: The target cell PCI ${bestNeighbor.pci} may have high Physical Resource Block (PRB) utilization or pending RRC reconfiguration.")

            val verdict = if (deltaAdvantage <= 3.0) {
                "Intra-Frequency Handover Margin / TTT Pending"
            } else {
                "Intra-Frequency Handover Delay / Cell Edge"
            }

            return ServingNeighborAnalysis(
                hasStrongerNeighbor = true,
                servingCell = servingCell,
                bestNeighborCell = bestNeighbor,
                deltaRsrpDb = delta,
                isInterFrequency = false,
                servingBandText = servingBandStr,
                neighborBandText = neighborBandStr,
                engineeringVerdict = verdict,
                primaryReason = "Intra-frequency neighbor PCI ${bestNeighbor.pci} is stronger by ${String.format(Locale.US, "%.1f", deltaAdvantage)} dB. Handover requires meeting the 3GPP A3 offset + hysteresis window.",
                standardsBasis = "3GPP TS 36.331 Event A3: Handover hysteresis margin prevents ping-pong transitions between co-channel cells.",
                confirmedFacts = confirmedFacts,
                networkHypotheses = hypotheses
            )
        }
    }
}
