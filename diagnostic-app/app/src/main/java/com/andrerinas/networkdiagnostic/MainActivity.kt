package com.andrerinas.networkdiagnostic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MAX_LOG_LINES = 500
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var shareButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var service: DiagnosticService? = null
    private var bound = false
    private var monitoring = false
    private var logLineCount = 0

    private val logListener = object : DiagnosticLogger.Listener {
        override fun onNewEntry(entry: String) {
            runOnUiThread {
                appendLogLine(entry)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as DiagnosticService.LocalBinder).service
            bound = true
            service?.logger?.addListener(logListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        shareButton = findViewById(R.id.shareButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        toggleButton.setOnClickListener {
            if (monitoring) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
        }

        shareButton.setOnClickListener {
            shareLog()
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.logger?.removeListener(logListener)
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        logTextView.text = ""
        logLineCount = 0

        val serviceIntent = Intent(this, DiagnosticService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        monitoring = true
        toggleButton.text = getString(R.string.stop_monitoring)
        statusText.text = getString(R.string.status_monitoring)
    }

    private fun stopMonitoring() {
        if (bound) {
            service?.logger?.removeListener(logListener)
            unbindService(serviceConnection)
            bound = false
        }

        stopService(Intent(this, DiagnosticService::class.java))
        service = null

        monitoring = false
        toggleButton.text = getString(R.string.start_monitoring)
        statusText.text = getString(R.string.status_idle)
    }

    private fun shareLog() {
        val logFile = service?.logger?.currentLogFile
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, getString(R.string.no_log), Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            logFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Network Diagnostic Log - ${logFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share log"))
    }

    private fun appendLogLine(line: String) {
        if (logLineCount >= MAX_LOG_LINES) {
            // Trim oldest lines
            val text = logTextView.text.toString()
            val trimIndex = text.indexOf('\n', text.length / 3)
            if (trimIndex > 0) {
                logTextView.text = text.substring(trimIndex + 1)
                logLineCount = logLineCount * 2 / 3
            }
        }
        logTextView.append(line)
        logTextView.append("\n")
        logLineCount++

        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // --- Permissions ---

    private fun checkPermissions(): Boolean {
        val required = getRequiredPermissions()
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return perms.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMonitoring()
            } else {
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        }
    }
}
