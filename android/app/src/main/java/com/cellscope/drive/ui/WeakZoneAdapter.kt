package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.analytics.WeakZone
import com.cellscope.drive.databinding.ItemWeakZoneBinding
import java.util.Locale

class WeakZoneAdapter(
    private var zones: List<WeakZone> = emptyList()
) : RecyclerView.Adapter<WeakZoneAdapter.WeakZoneViewHolder>() {

    fun updateZones(newZones: List<WeakZone>) {
        zones = newZones
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeakZoneViewHolder {
        val binding = ItemWeakZoneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WeakZoneViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WeakZoneViewHolder, position: Int) {
        holder.bind(zones[position])
    }

    override fun getItemCount(): Int = zones.size

    class WeakZoneViewHolder(private val binding: ItemWeakZoneBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(zone: WeakZone) {
            binding.tvZoneTitle.text = "Weak Zone #${zone.id} (~${String.format(Locale.US, "%.0f", zone.lengthMeters)}m corridor)"
            binding.tvZoneRsrp.text = "${String.format(Locale.US, "%.1f", zone.avgRsrp)} dBm avg"
            binding.tvZoneDiagnosis.text = zone.diagnosis

            val topRemediation = zone.remediationOptions.firstOrNull()
            if (topRemediation != null) {
                binding.tvZoneRemediation.text = "Recommended Action: [${topRemediation.category}] ${topRemediation.title} (${(topRemediation.feasibilityScore * 100).toInt()}% Feasibility)"
            } else {
                binding.tvZoneRemediation.text = "No immediate physical remediation required."
            }
        }
    }
}
