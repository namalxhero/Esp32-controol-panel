package com.nipuna.esp32controller

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CommandAdapter(private val onClick: (String) -> Unit) :
    RecyclerView.Adapter<CommandAdapter.VH>() {

    private var commands: List<String> = emptyList()

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val chip: TextView = itemView.findViewById(R.id.cmdChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_command, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cmd = commands[position]
        holder.chip.text = cmd
        holder.chip.setOnClickListener { onClick(cmd) }
    }

    override fun getItemCount(): Int = commands.size

    /** Replaces the command list with exactly what the connected ESP32 reported (#CMDS:). */
    fun submitList(newCommands: List<String>) {
        commands = newCommands
        notifyDataSetChanged()
    }
}
