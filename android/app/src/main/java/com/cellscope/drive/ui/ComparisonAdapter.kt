package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.R
import com.cellscope.drive.databinding.ItemCellComparisonBinding
import com.cellscope.drive.model.ServingStatus
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
    val sampleCount: Int,
    val servingStatus: ServingStatus = if (isServing) ServingStatus.PRIMARY_SERVING else ServingStatus.NEIGHBOR,
    val rat: String? = "LTE",
    val channelType: String? = "EARFCN"
)

class ComparisonAdapter : RecyclerView.Adapter<ComparisonAdapter.ComparisonViewHolder>() {

    private var allItems: List<CellComparisonItem> = emptyList()
    private var displayedItems: List<CellComparisonItem> = emptyList()

    private var filterRole: String = "ALL" // ALL, SERVING, NEIGHBOR
    private var filterBand: Int? = null

    var onFilterResultListener: ((itemCount: Int) -> Unit)? = null

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
        try {
            notifyDataSetChanged()
        } catch (_: Exception) {
            // Handled when mObservable is uninitialized in JVM unit test environment
        }
        onFilterResultListener?.invoke(displayedItems.size)
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

            binding.tvCompPci.text = when (item.rat?.uppercase(Locale.US)) {
                "2G", "GSM", "2G_GSM" -> "BSIC ${item.pci}"
                "3G", "WCDMA", "3G_WCDMA", "UMTS" -> "PSC ${item.pci}"
                "5G", "5G_NR", "NR" -> "NR-PCI ${item.pci}"
                else -> "PCI ${item.pci}"
            }

            when (item.servingStatus) {
                ServingStatus.PRIMARY_SERVING -> {
                    binding.tvCompRoleBadge.text = "SERVING (PCELL)"
                    binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.rf_excellent))
                }
                ServingStatus.SECONDARY_SERVING -> {
                    binding.tvCompRoleBadge.text = "SERVING (SCELL - CA)"
                    binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.rf_good))
                }
                ServingStatus.SERVING_UNCONFIRMED -> {
                    binding.tvCompRoleBadge.text = "UNCONFIRMED"
                    binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.rf_fair))
                }
                ServingStatus.NEIGHBOR -> {
                    binding.tvCompRoleBadge.text = "NEIGHBOR"
                    binding.tvCompRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
            }

            val chLabel = when (item.rat) {
                "2G", "2G_GSM" -> "ARFCN"
                "3G", "3G_WCDMA" -> "UARFCN"
                "5G", "5G_NR" -> "NR-ARFCN"
                else -> "EARFCN"
            }
            val sigLabel = when (item.rat) {
                "2G", "2G_GSM" -> "RSSI"
                "3G", "3G_WCDMA" -> "RSCP"
                "5G", "5G_NR" -> "SS-RSRP"
                else -> "RSRP"
            }

            val bandStr = if (item.bandNumber != null) {
                "Band ${item.bandNumber} (${item.bandName ?: "Carrier"}) • $chLabel ${item.earfcn ?: "—"} • DL: ${item.dlFreqMhz ?: "—"} MHz"
            } else if (item.earfcn != null) {
                "$chLabel ${item.earfcn}"
            } else {
                "Channel: Unknown"
            }
            binding.tvCompBandFreq.text = bandStr

            val rsrpText = item.avgRsrp?.let {
                String.format(Locale.US, "$sigLabel: %.1f dBm [%d..%d]", it, item.minRsrp ?: 0, item.maxRsrp ?: 0)
            } ?: "$sigLabel: — dBm"
            binding.tvCompRsrp.text = rsrpText

            val rsrqLabel = if (item.rat == "5G" || item.rat == "5G_NR") "SS-RSRQ" else "RSRQ"
            val rsrqText = item.avgRsrq?.let {
                String.format(Locale.US, "$rsrqLabel: %.1f dB", it)
            } ?: "$rsrqLabel: — dB"
            binding.tvCompRsrq.text = rsrqText

            val sinrLabel = if (item.rat == "5G" || item.rat == "5G_NR") "SS-SINR" else "SINR"
            val sinrText = item.avgSinr?.let {
                String.format(Locale.US, "$sinrLabel: %.1f dB", it)
            } ?: "$sinrLabel: — dB"
            binding.tvCompSinr.text = sinrText

            val tacLabel = if (item.rat == "2G" || item.rat == "3G") "LAC" else "TAC"
            val eciLabel = if (item.rat == "5G") "NCI" else if (item.rat == "2G" || item.rat == "3G") "CID" else "ECI"
            val tacStr = item.tac?.let { "$tacLabel: $it" } ?: "$tacLabel: —"
            val eciStr = item.eci?.let { "$eciLabel: $it" } ?: "$eciLabel: —"
            binding.tvCompIds.text = "$tacStr • $eciStr"

            binding.tvCompSamples.text = "${item.sampleCount} samples"
        }
    }
}
