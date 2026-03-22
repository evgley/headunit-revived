package com.andrerinas.networkdiagnostic

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class DiagnosticLogger(context: Context) {

    interface Listener {
        fun onNewEntry(entry: String)
    }

    private val logDir = File(context.filesDir, "logs").also { it.mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var logFile: File? = null
    private var writer: PrintWriter? = null
    private val startElapsed = SystemClock.elapsedRealtime()

    val currentLogFile: File? get() = logFile

    fun start() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(logDir, "netdiag_${timestamp}.txt")
        writer = PrintWriter(FileWriter(logFile!!, true), true)

        val header = buildString {
            appendLine("=== Network Diagnostic Log ===")
            appendLine("=== Started: ${dateFormat.format(Date())} ===")
            appendLine("=== Device: ${android.os.Build.MODEL} (${android.os.Build.MANUFACTURER}) ===")
            appendLine("=== Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) ===")
            appendLine("=== Build: ${android.os.Build.DISPLAY} ===")
            appendLine()
        }
        writer?.print(header)
    }

    fun stop() {
        log("SYSTEM", "Monitoring stopped")
        writer?.close()
        writer = null
    }

    fun log(tag: String, message: String) {
        val elapsed = SystemClock.elapsedRealtime() - startElapsed
        val wallTime = dateFormat.format(Date())
        val entry = "$wallTime [+${elapsed}ms] [$tag] $message"

        writer?.println(entry)
        for (listener in listeners) {
            listener.onNewEntry(entry)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
