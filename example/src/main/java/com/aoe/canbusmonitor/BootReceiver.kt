package com.aoe.canbusmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                context.startForegroundService(Intent(context, TurnSignalService::class.java))
            } catch (t: Throwable) {
                RuntimeState.lastError = "Boot start: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}
