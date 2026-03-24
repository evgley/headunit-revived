package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in BOOT_ACTIONS) return

        AppLog.i("Boot auto-start: received action=$action")

        if (!Settings.isAutoStartOnBootEnabled(context)) {
            AppLog.i("Boot auto-start: disabled, skipping")
            return
        }

        AppLog.i("Boot auto-start: starting AapService with BOOT_START (trigger=$action)")
        val serviceIntent = Intent(context, AapService::class.java).apply {
            putExtra(EXTRA_BOOT_START, true)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val EXTRA_BOOT_START = "com.andrerinas.headunitrevived.EXTRA_BOOT_START"

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
