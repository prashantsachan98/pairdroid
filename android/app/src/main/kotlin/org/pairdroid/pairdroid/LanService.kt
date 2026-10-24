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
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("management", "Visible device management", NotificationManager.IMPORTANCE_LOW))
        ticker.scheduleAtFixedRate({
            try { engine.tick() } catch(e: Exception) { engine.error = "Policy evaluation: ${e.message}" }
            if(engine.capture.active && android.os.SystemClock.elapsedRealtime() - engine.lastFramePoll > 15000) engine.stopSharing()
            getSystemService(NotificationManager::class.java).notify(1, notification())
        }, 1, 5, TimeUnit.SECONDS)
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopShare = PendingIntent.getService(this,1,Intent(this,LanService::class.java).setAction("STOP_SHARE"),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,2,Intent(this,LanService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"management").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("PairDroid management is visible")
            .setContentText(if(engine.capture.active) "Screen sharing active • Tap Stop Sharing to end" else "Local network service active • No screen capture")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop Sharing",stopShare).build())
            .addAction(Notification.Action.Builder(null,"Stop service",stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if(intent?.action == "STOP_SHARE") { engine.stopSharing(); getSystemService(NotificationManager::class.java).notify(1,notification()); return START_NOT_STICKY }
        try {
            val capture = intent?.action == "CAPTURE"
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if(capture || engine.capture.active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
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
