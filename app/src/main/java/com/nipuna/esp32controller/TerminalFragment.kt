package com.nipuna.esp32controller

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TerminalFragment : Fragment(R.layout.fragment_terminal) {

    private lateinit var logRecycler: RecyclerView
    private lateinit var cmdRecycler: RecyclerView
    private lateinit var cmdInput: EditText
    private val logAdapter = LogAdapter()
    private lateinit var cmdAdapter: CommandAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logRecycler = view.findViewById(R.id.logRecycler)
        cmdRecycler = view.findViewById(R.id.cmdRecycler)
        cmdInput = view.findViewById(R.id.cmdInput)

        logRecycler.layoutManager = LinearLayoutManager(requireContext())
        logRecycler.adapter = logAdapter

        cmdAdapter = CommandAdapter { command -> sendCommand(command) }
        cmdRecycler.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        cmdRecycler.adapter = cmdAdapter

        view.findViewById<View>(R.id.btnSend).setOnClickListener { sendFromInput() }
        cmdInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendFromInput()
                true
            } else false
        }
    }

    private fun sendFromInput() {
        val text = cmdInput.text.toString().trim()
        if (text.isEmpty()) return
        sendCommand(text)
        cmdInput.text.clear()
    }

    private fun sendCommand(command: String) {
        (activity as? MainActivity)?.sendCommand(command)
        appendLog(command, outgoing = true)
    }

    fun appendLog(text: String, outgoing: Boolean) {
        if (!isAdded) return
        logAdapter.append(LogEntry(text, outgoing))
        logRecycler.scrollToPosition(logAdapter.itemCount - 1)
    }

    fun updateCommands(commands: List<String>) {
        if (!isAdded) return
        cmdAdapter.submitList(commands)
    }

    fun clearLog() {
        logAdapter.clear()
    }
}
