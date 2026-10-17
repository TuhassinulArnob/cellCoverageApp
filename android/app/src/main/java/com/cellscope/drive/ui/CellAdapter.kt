package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.R
import com.cellscope.drive.databinding.ItemCellBinding
import com.cellscope.drive.model.ObservedCell
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

            // Role Badge
            binding.tvCellRole.text = if (cell.isServing) "SERVING" else "NEIGHBOR"
            val roleBgColor = if (cell.isServing) R.color.rf_excellent else R.color.surface_variant
            val roleTextColor = if (cell.isServing) R.color.background else R.color.text_secondary
            binding.tvCellRole.backgroundTintList = ContextCompat.getColorStateList(ctx, roleBgColor)
            binding.tvCellRole.setTextColor(ContextCompat.getColor(ctx, roleTextColor))

            // PCI
            binding.tvCellPci.text = cell.pci?.let { "PCI $it" } ?: "PCI —"

            // RSRP
            if (cell.rsrpDbm != null) {
                binding.tvCellRsrp.text = "${cell.rsrpDbm} dBm"
                val rsrpColor = when (cell.rsrpRating) {
                    "EXCELLENT" -> R.color.rf_excellent
                    "GOOD" -> R.color.rf_good
                    "FAIR" -> R.color.rf_fair
                    else -> R.color.rf_poor
                }
                binding.tvCellRsrp.setTextColor(ContextCompat.getColor(ctx, rsrpColor))
            } else {
                binding.tvCellRsrp.text = "— dBm"
                binding.tvCellRsrp.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            // Band & Frequency derivation
            val bandInfo = LteBandTable.resolveEarfcn(cell.earfcn)
            val bandText = if (bandInfo != null) {
                "Band ${bandInfo.bandNumber} (${bandInfo.designation}) • EARFCN ${cell.earfcn} • DL: ${bandInfo.dlFrequencyMhz} MHz"
            } else if (cell.earfcn != null) {
                "EARFCN ${cell.earfcn} • Frequency: Unmapped"
            } else {
                "Carrier: Unreported by RIL"
            }
            binding.tvCellBand.text = bandText

            // RSRQ, SINR, EARFCN
            binding.tvCellRsrq.text = cell.rsrqDb?.let { "RSRQ: $it dB" } ?: "RSRQ: — dB"
            binding.tvCellSinr.text = cell.sinrDb?.let { "SINR: ${String.format(Locale.US, "%.1f", it)} dB" } ?: "SINR: Unavailable"
            binding.tvCellEarfcn.text = cell.earfcn?.let { "EARFCN: $it" } ?: "EARFCN: —"

            // Identifiers: Only serving cells broadcast SIB1 TAC & ECI
            if (cell.isServing) {
                binding.tvCellTac.text = cell.tac?.let { "TAC: $it" } ?: "TAC: —"
                binding.tvCellEci.text = cell.eci?.let { "ECI: $it" } ?: "ECI: —"
            } else {
                binding.tvCellTac.text = "TAC: N/A (Neighbor)"
                binding.tvCellEci.text = "ECI: N/A (Neighbor)"
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
