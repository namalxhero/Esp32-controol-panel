package com.nipuna.esp32controller

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

data class LogEntry(val text: String, val outgoing: Boolean, val timestamp: Long = System.currentTimeMillis())

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val entries = mutableListOf<LogEntry>()

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val time: android.widget.TextView = itemView.findViewById(R.id.lineTime)
        val text: android.widget.TextView = itemView.findViewById(R.id.lineText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_line, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.time.text = DateFormat.format("HH:mm:ss", Date(entry.timestamp))
        val prefix = if (entry.outgoing) "> " else "  "
        holder.text.text = prefix + entry.text
        val colorRes = if (entry.outgoing) R.color.accent_amber else R.color.text_primary
        holder.text.setTextColor(holder.itemView.context.getColor(colorRes))
    }

    override fun getItemCount(): Int = entries.size

    fun append(entry: LogEntry) {
        entries.add(entry)
        notifyItemInserted(entries.size - 1)
        if (entries.size > 500) {
            entries.removeAt(0)
            notifyItemRemoved(0)
        }
    }

    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }
}
