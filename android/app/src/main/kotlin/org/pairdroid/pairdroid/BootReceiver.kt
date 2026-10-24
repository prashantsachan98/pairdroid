package org.pairdroid.pairdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts the child LAN service automatically on device boot.
 * Registered only in the child flavor manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Only auto-start if a parent is already paired (vault has a stored parent pin)
        val vault = Vault(context)
        val stored = vault.read()
        if (stored.optString("parent").isNotEmpty()) {
            val svc = Intent(context, LanService::class.java).setAction("SILENT_START")
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
        }
    }
}
