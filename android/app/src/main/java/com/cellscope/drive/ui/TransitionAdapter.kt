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
            val displayLabel = when (t.eventType.uppercase(Locale.US)) {
                "CONFIRMED_HANDOVER" -> "CONFIRMED HANDOVER"
                "INTRA_FREQ", "INTRA_FREQUENCY" -> "INTRA-FREQUENCY TRANSITION"
                "INTER_FREQ", "INTER_FREQUENCY" -> "INTER-FREQUENCY TRANSITION"
                "INTER_RAT", "RAT_MOBILITY" -> "INTER-RAT TRANSITION"
                "CELL_RESELECTION" -> "CELL RESELECTION"
                "OBSERVED_CELL_CHANGE", "OBSERVED_TRANSITION" -> "OBSERVED CELL CHANGE"
                "PING_PONG", "POSSIBLE_HANDOVER" -> "PING-PONG CANDIDATE"
                "SIGNAL_LOSS" -> "SIGNAL LOSS"
                else -> t.eventType.replace("_", " ")
            }
            binding.tvTransitionType.text = displayLabel

            val badgeColor = when (t.eventType.uppercase(Locale.US)) {
                "CONFIRMED_HANDOVER" -> R.color.rf_excellent
                "INTRA_FREQ", "INTRA_FREQUENCY" -> R.color.rf_good
                "INTER_FREQ", "INTER_FREQUENCY" -> R.color.rf_good
                "INTER_RAT", "RAT_MOBILITY" -> R.color.rf_fair
                "CELL_RESELECTION" -> R.color.rf_fair
                "PING_PONG", "POSSIBLE_HANDOVER" -> R.color.rf_poor
                "SIGNAL_LOSS" -> R.color.rf_poor
                else -> R.color.rf_fair
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
