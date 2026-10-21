package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.R
import com.cellscope.drive.databinding.ItemCellBinding
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus
import com.cellscope.drive.telephony.CellularBandResolver
import com.cellscope.drive.telephony.LteBandTable
import java.util.Locale

class CellAdapter(
    private var cells: List<ObservedCell> = emptyList()
) : RecyclerView.Adapter<CellAdapter.CellViewHolder>() {

    fun updateCells(newCells: List<ObservedCell>) {
        cells = newCells
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellViewHolder {
        val binding = ItemCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CellViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CellViewHolder, position: Int) {
        holder.bind(cells[position])
    }

    override fun getItemCount(): Int = cells.size

    class CellViewHolder(private val binding: ItemCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: ObservedCell) {
            val ctx = binding.root.context

            // Authoritative Serving Role Badge
            val (roleText, roleBgColor, roleTextColor) = when (cell.servingStatus) {
                ServingStatus.PRIMARY_SERVING -> Triple(
                    "SERVING (PCELL)",
                    R.color.rf_excellent,
                    R.color.background
                )
                ServingStatus.SECONDARY_SERVING -> Triple(
                    "SERVING (SCELL - CA)",
                    R.color.rf_good,
                    R.color.background
                )
                ServingStatus.SERVING_UNCONFIRMED -> Triple(
                    "UNCONFIRMED",
                    R.color.rf_fair,
                    R.color.background
                )
                ServingStatus.NEIGHBOR -> Triple(
                    "NEIGHBOR",
                    R.color.surface_variant,
                    R.color.text_secondary
                )
            }

            val ratPrefix = when {
                cell.isGsm -> "2G "
                cell.isWcdma -> "3G "
                cell.is5gNr -> "5G "
                else -> "4G "
            }

            binding.tvCellRole.text = "$ratPrefix$roleText"
            binding.tvCellRole.backgroundTintList = ContextCompat.getColorStateList(ctx, roleBgColor)
            binding.tvCellRole.setTextColor(ContextCompat.getColor(ctx, roleTextColor))

            // PCI
            binding.tvCellPci.text = when {
                cell.isGsm -> cell.pci?.let { "BSIC $it" } ?: "BSIC —"
                cell.isWcdma -> cell.pci?.let { "PSC $it" } ?: "PSC —"
                cell.is5gNr -> cell.pci?.let { "NR-PCI $it" } ?: "PCI —"
                else -> cell.pci?.let { "PCI $it" } ?: "PCI —"
            }

            // RSRP
            // Signal display by RAT
            val signalVal = cell.primarySignalDbm ?: cell.rsrpDbm ?: cell.rssiDbm
            val signalUnit = when {
                cell.isGsm -> "dBm (RSSI)"
                cell.isWcdma -> "dBm (RSCP)"
                cell.is5gNr -> "dBm (SS-RSRP)"
                else -> "dBm (RSRP)"
            }
            if (signalVal != null) {
                binding.tvCellRsrp.text = "$signalVal $signalUnit"
                val rsrpColor = when {
                    signalVal >= -80 -> R.color.rf_excellent
                    signalVal >= -90 -> R.color.rf_good
                    signalVal >= -100 -> R.color.rf_fair
                    else -> R.color.rf_poor
                }
                binding.tvCellRsrp.setTextColor(ContextCompat.getColor(ctx, rsrpColor))
            } else {
                binding.tvCellRsrp.text = "— dBm"
                binding.tvCellRsrp.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            // Band & Frequency derivation strictly by RAT
            val carrierInfo = CellularBandResolver.resolve(cell)
            val bandText = if (carrierInfo != null) {
                "${carrierInfo.bandName} • ${carrierInfo.channelNumber} • DL: ${carrierInfo.dlCenterFreqMhz} MHz"
            } else when {
                cell.isGsm -> "GSM 900/1800 • ARFCN ${cell.channelNumber ?: "—"}"
                cell.isWcdma -> "3G WCDMA • UARFCN ${cell.channelNumber ?: "—"}"
                cell.is5gNr -> "5G NR • NR-ARFCN ${cell.nrArfcn ?: cell.channelNumber ?: "—"}"
                cell.earfcn != null -> "EARFCN ${cell.earfcn} • Frequency: Unmapped"
                else -> "Carrier: Unreported by RIL"
            }
            binding.tvCellBand.text = bandText

            // Channel Number and Secondary Metrics by RAT
            when {
                cell.isGsm -> {
                    val ch = cell.channelNumber ?: cell.earfcn?.takeIf { it in 0..1023 }
                    binding.tvCellEarfcn.text = ch?.let { "ARFCN: $it" } ?: "ARFCN: —"
                    binding.tvCellRsrq.text = cell.pci?.let { "BSIC: $it" } ?: "BSIC: —"
                    binding.tvCellSinr.text = signalVal?.let { "RxLev: $it dBm" } ?: "RxLev: —"
                }
                cell.isWcdma -> {
                    val ch = cell.channelNumber ?: cell.earfcn?.takeIf { it in 0..16383 }
                    binding.tvCellEarfcn.text = ch?.let { "UARFCN: $it" } ?: "UARFCN: —"
                    binding.tvCellRsrq.text = cell.pci?.let { "PSC: $it" } ?: "PSC: —"
                    binding.tvCellSinr.text = signalVal?.let { "RSCP: $it dBm" } ?: "RSCP: —"
                }
                cell.is5gNr -> {
                    binding.tvCellEarfcn.text = (cell.nrArfcn ?: cell.channelNumber)?.let { "NR-ARFCN: $it" } ?: "NR-ARFCN: —"
                    binding.tvCellRsrq.text = (cell.csiRsrqDb ?: cell.rsrqDb)?.let { "SS-RSRQ: $it dB" } ?: "SS-RSRQ: — dB"
                    val sinrVal = cell.csiSinrDb ?: cell.sinrDb
                    binding.tvCellSinr.text = sinrVal?.let { "SS-SINR: ${String.format(Locale.US, "%.1f", it)} dB" } ?: "SS-SINR: Unavailable"
                }
                else -> {
                    binding.tvCellEarfcn.text = (cell.channelNumber ?: cell.earfcn)?.let { "EARFCN: $it" } ?: "EARFCN: —"
                    binding.tvCellRsrq.text = cell.rsrqDb?.let { "RSRQ: $it dB" } ?: "RSRQ: — dB"
                    binding.tvCellSinr.text = cell.sinrDb?.let { "SINR: ${String.format(Locale.US, "%.1f", it)} dB" } ?: "SINR: Unavailable"
                }
            }

            // Identifiers: PCell broadcasts SIB1 TAC & ECI
            val tacLabel = if (cell.isGsm || cell.isWcdma) "LAC" else "TAC"
            val eciLabel = if (cell.is5gNr) "NCI" else if (cell.isGsm || cell.isWcdma) "CID" else "ECI"
            val idVal = if (cell.is5gNr) cell.nci ?: cell.eci else cell.eci

            when (cell.servingStatus) {
                ServingStatus.PRIMARY_SERVING -> {
                    binding.tvCellTac.text = cell.tac?.let { "$tacLabel: $it" } ?: "$tacLabel: —"
                    binding.tvCellEci.text = idVal?.let { "$eciLabel: $it" } ?: "$eciLabel: —"
                }
                ServingStatus.SECONDARY_SERVING -> {
                    binding.tvCellTac.text = cell.tac?.let { "$tacLabel: $it" } ?: "$tacLabel: SCell (CA)"
                    binding.tvCellEci.text = idVal?.let { "$eciLabel: $it" } ?: "$eciLabel: SCell (CA)"
                }
                ServingStatus.SERVING_UNCONFIRMED -> {
                    binding.tvCellTac.text = cell.tac?.let { "$tacLabel: $it" } ?: "$tacLabel: Unconfirmed"
                    binding.tvCellEci.text = idVal?.let { "$eciLabel: $it" } ?: "$eciLabel: Unconfirmed"
                }
                ServingStatus.NEIGHBOR -> {
                    binding.tvCellTac.text = "$tacLabel: N/A (Neighbor)"
                    binding.tvCellEci.text = "$eciLabel: N/A (Neighbor)"
                }
            }

            // RSSI or Timing Advance
            if (cell.timingAdvance != null) {
                val estDistM = cell.timingAdvance * 78
                binding.tvCellRssi.text = "TA: ${cell.timingAdvance} (~${estDistM}m)"
            } else {
                binding.tvCellRssi.text = cell.rssiDbm?.let { "RSSI: $it dBm" } ?: "RSSI: — dBm"
            }
        }
    }
}
