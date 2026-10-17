package com.cellscope.drive.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private var sessions: List<DriveSession>,
    private val onExportClick: (DriveSession) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)

    inner class SessionViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]
        holder.binding.tvSessionItemName.text = session.sessionName
        holder.binding.tvSessionItemDate.text = dateFormat.format(Date(session.startTimeMs))
        holder.binding.tvSessionItemSamples.text = "${session.sampleCount} GPS/LTE samples"

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
