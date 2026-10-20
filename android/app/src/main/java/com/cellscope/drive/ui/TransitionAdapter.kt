package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.R
import com.cellscope.drive.data.entity.CellTransitionEntity
import com.cellscope.drive.databinding.ItemTransitionBinding
import java.util.Locale

class TransitionAdapter(
    private var transitions: List<CellTransitionEntity> = emptyList(),
    var onItemClickListener: ((CellTransitionEntity) -> Unit)? = null
) : RecyclerView.Adapter<TransitionAdapter.TransitionViewHolder>() {

    fun updateTransitions(newTransitions: List<CellTransitionEntity>) {
        transitions = newTransitions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransitionViewHolder {
        val binding = ItemTransitionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransitionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransitionViewHolder, position: Int) {
        val item = transitions[position]
        holder.bind(item)
        holder.itemView.setOnClickListener {
            onItemClickListener?.invoke(item)
        }
    }

    override fun getItemCount(): Int = transitions.size

    class TransitionViewHolder(private val binding: ItemTransitionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(t: CellTransitionEntity) {
            val ctx = binding.root.context
            binding.tvTransitionType.text = t.eventType.replace("_", " ")

            val badgeColor = when (t.eventType) {
                "CONFIRMED_HANDOVER" -> R.color.rf_excellent
                "OBSERVED_TRANSITION" -> R.color.rf_good
                "CELL_RESELECTION" -> R.color.rf_fair
                else -> R.color.rf_poor
            }
            binding.tvTransitionType.backgroundTintList = ContextCompat.getColorStateList(ctx, badgeColor)

            val timeStr = t.timestampIso.substringAfter("T").substringBefore("Z")
            binding.tvTransitionTime.text = timeStr
            binding.tvTransitionConfidence.text = String.format(Locale.US, "%.0f%% Conf.", t.confidenceScore * 100f)

            val fromStr = "PCI ${t.fromPci ?: "?"} (${t.fromRsrp?.let { "$it dBm" } ?: "—"})"
            val toStr = "PCI ${t.toPci ?: "?"} (${t.toRsrp?.let { "$it dBm" } ?: "—"})"
            binding.tvTransitionRoute.text = "$fromStr  ➔  $toStr"

            binding.tvTransitionDetail.text = t.details
        }
    }
}
