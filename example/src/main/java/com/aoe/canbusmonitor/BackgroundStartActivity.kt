package com.aoe.canbusmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Launcher entry intended for DUDUOS Automatic Task: Vehicle ignition -> Open app.
 * It only starts the foreground service and immediately finishes, so no configuration UI stays visible.
 */
class BackgroundStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startForegroundService(Intent(this, TurnSignalService::class.java))
        } catch (t: Throwable) {
            RuntimeState.lastError = "Wake start: ${t.javaClass.simpleName}: ${t.message}"
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
