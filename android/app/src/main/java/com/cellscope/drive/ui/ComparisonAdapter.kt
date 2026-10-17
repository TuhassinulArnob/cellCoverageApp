package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.R
import com.cellscope.drive.databinding.ItemCellComparisonBinding
import com.cellscope.drive.telephony.LteBandTable
import java.util.Locale

data class CellComparisonItem(
    val pci: Int,
    val earfcn: Int?,
    val bandNumber: Int?,
    val bandName: String?,
    val dlFreqMhz: Double?,
    val ulFreqMhz: Double?,
    val isServing: Boolean,
    val tac: Int?,
    val eci: Long?,
    val avgRsrp: Double?,
    val minRsrp: Int?,
    val maxRsrp: Int?,
    val avgRsrq: Double?,
    val avgSinr: Double?,
    val sampleCount: Int
)

class ComparisonAdapter : RecyclerView.Adapter<ComparisonAdapter.ComparisonViewHolder>() {

    private var allItems: List<CellComparisonItem> = emptyList()
    private var displayedItems: List<CellComparisonItem> = emptyList()

    private var filterRole: String = "ALL" // ALL, SERVING, NEIGHBOR
    private var filterBand: Int? = null

    fun setItems(items: List<CellComparisonItem>) {
        this.allItems = items
        applyFilter()
    }

    fun setFilterRole(role: String) {
        this.filterRole = role
        applyFilter()
    }

    fun setFilterBand(band: Int?) {
        this.filterBand = band
        applyFilter()
    }

    private fun applyFilter() {
        displayedItems = allItems.filter { item ->
            val matchRole = when (filterRole) {
                "SERVING" -> item.isServing
                "NEIGHBOR" -> !item.isServing
                else -> true
            }
            val matchBand = if (filterBand != null) item.bandNumber == filterBand else true
            matchRole && matchBand
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComparisonViewHolder {
        val binding = ItemCellComparisonBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ComparisonViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ComparisonViewHolder, position: Int) {
        holder.bind(displayedItems[position])
    }

    override fun getItemCount(): Int = displayedItems.size

    class ComparisonViewHolder(
        private val binding: ItemCellComparisonBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CellComparisonItem) {
            val context = itemView.context

            binding.tvCompPci.text = "PCI ${item.pci}"

            if (item.isServing) {
                binding.tvCompRoleBadge.text = "SERVING"
                binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.rf_excellent))
            } else {
                binding.tvCompRoleBadge.text = "NEIGHBOR"
                binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            val bandStr = if (item.bandNumber != null) {
                "Band ${item.bandNumber} (${item.bandName ?: "LTE"}) • EARFCN ${item.earfcn ?: "—"} • DL: ${item.dlFreqMhz ?: "—"} MHz"
            } else if (item.earfcn != null) {
                "EARFCN ${item.earfcn}"
            } else {
                "Channel: Unknown"
            }
            binding.tvCompBandFreq.text = bandStr

            val rsrpText = item.avgRsrp?.let {
                String.format(Locale.US, "RSRP: %.1f dBm [%d..%d]", it, item.minRsrp ?: 0, item.maxRsrp ?: 0)
            } ?: "RSRP: — dBm"
            binding.tvCompRsrp.text = rsrpText

            val rsrqText = item.avgRsrq?.let {
                String.format(Locale.US, "RSRQ: %.1f dB", it)
            } ?: "RSRQ: — dB"
            binding.tvCompRsrq.text = rsrqText

            val sinrText = item.avgSinr?.let {
                String.format(Locale.US, "SINR: %.1f dB", it)
            } ?: "SINR: — dB"
            binding.tvCompSinr.text = sinrText

            val tacStr = item.tac?.let { "TAC: $it" } ?: "TAC: —"
            val eciStr = item.eci?.let { "ECI: $it" } ?: "ECI: —"
            binding.tvCompIds.text = "$tacStr • $eciStr"

            binding.tvCompSamples.text = "${item.sampleCount} samples"
        }
    }
}
