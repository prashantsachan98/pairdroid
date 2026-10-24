package org.pairdroid.pairdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Secret dialer code receiver.
 * Dial  *#*#7283#*#*  on the child phone to silently open the setup UI.
 * The child cannot discover this code — it is not listed anywhere on the device.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launch)
    }
}
