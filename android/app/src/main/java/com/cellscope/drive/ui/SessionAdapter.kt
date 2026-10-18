package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import com.cellscope.drive.util.TelecomTimeUtils

class SessionAdapter(
    private var sessions: List<DriveSession>,
    private val onExportClick: (DriveSession) -> Unit,
    private val onItemClick: ((DriveSession) -> Unit)? = null
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    inner class SessionViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        holder.binding.tvSessionItemName.text = session.sessionName
        holder.binding.tvSessionItemDate.text = "${TelecomTimeUtils.formatBdtPretty(session.startTimeMs)} (BST)"
        holder.binding.tvSessionItemSamples.text = "${session.sampleCount} GPS/LTE samples"

        holder.binding.root.setOnClickListener {
            onItemClick?.invoke(session)
        }

        holder.binding.btnExportSessionItem.setOnClickListener {
            onExportClick(session)
        }
    }

    override fun getItemCount(): Int = sessions.size

    fun updateData(newSessions: List<DriveSession>) {
        sessions = newSessions
        notifyDataSetChanged()
    }
}
