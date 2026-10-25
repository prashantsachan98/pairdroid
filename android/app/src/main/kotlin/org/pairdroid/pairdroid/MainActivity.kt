package org.pairdroid.pairdroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val work = Executors.newFixedThreadPool(3)
    private var consentPeer: String? = null
    private val engine get() = LanEngine.get(this)
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Auto-start the child service silently if this is the child flavor and permissions are ready.
        if (packageName.endsWith(".child")) autoStartServiceIfReady()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger,"org.pairdroid/native").setMethodCallHandler { call, result ->
            val args = runCatching { JSONObject(call.arguments as? String ?: "{}") }.getOrElse { result.error("arguments","Invalid JSON",null); return@setMethodCallHandler }
            fun success(value: Any = JSONObject()) = result.success(value.toString())
            try {
                when(call.method) {
                    "permissions" -> {
                        val needed = mutableListOf<String>()
                        if(packageName.endsWith(".child") && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
                        if(Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) needed.add("android.permission.ACCESS_LOCAL_NETWORK")
                        if(needed.isNotEmpty()) requestPermissions(needed.toTypedArray(),20)
                        success()
                    }
                    "usageSettings" -> { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))); success() }
                    "appSettings" -> { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))); success() }
                    "start" -> {
                        check(packageName.endsWith(".child"))
                        if(Build.VERSION.SDK_INT >= 33) check(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Allow notifications before starting visible management" }
                        if(Build.VERSION.SDK_INT >= 37) check(checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) { "Allow local network access first" }
                        startForegroundService(Intent(this,LanService::class.java)); success()
                    }
                    "stop" -> { stopService(Intent(this,LanService::class.java)); success() }
                    "capture" -> {
                        val pin = engine.approveSharing()
                        if(Build.VERSION.SDK_INT >= 30) {
                            if(engine.capture.accessibilityAvailable()) {
                                // Accessibility capture enabled once — start immediately, no dialog.
                                engine.beginSharing(pin)
                            } else {
                                // One-time setup: enable "System Services" in Accessibility, then approve again.
                                engine.journal.add("Accessibility capture not enabled; opening Settings for one-time setup")
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        } else {
                            check(consentPeer == null) { "System consent already pending" }
                            consentPeer = pin
                            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),30)
                        }
                        success()
                    }
                    "accessibilitySettings" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); success() }
                    else -> work.execute {
                        try {
                            val value: Any = when(call.method) {
                                "state" -> engine.state().put("role",if(packageName.endsWith(".parent")) "parent" else "child")
                                "discover" -> { engine.discover(); JSONObject() }
                                "stopDiscovery" -> { engine.stopDiscovery(); JSONObject() }
                                "openPairing" -> { engine.openPairing(); JSONObject() }
                                "approve" -> { engine.approve(args.getString("pin"),args.getBoolean("approve")); JSONObject() }
                                "revoke" -> { engine.revoke(); JSONObject() }
                                "clearPolicy" -> { engine.data.clearPolicy(); engine.journal.add("Policy cleared on device"); JSONObject() }
                                "rotate" -> { engine.rotate(); JSONObject() }
                                // Park (not full stop) — projection token stays alive for silent reconnect.
                                "stopSharing" -> { engine.parkSharing(); JSONObject() }
                                "forget" -> { engine.forget(args.getString("pin")); JSONObject() }
                                "setAutoApprovePin" -> { engine.setAutoApprovePin(args.getString("pin")); JSONObject() }
                                "rpc" -> engine.rpc(args.getJSONObject("target"),args.getString("cmd"),args.optJSONObject("payload") ?: JSONObject())
                                else -> throw IllegalArgumentException("Unknown method")
                            }
                            runOnUiThread { success(value) }
                        } catch(e: Exception) { runOnUiThread { result.error("native",e.message,null) } }
                    }
                }
            } catch(e: Exception) { result.error("native",e.message,null) }
        }
    }
    /** Silently starts the LAN service on child launch if notification permission is granted. */
    private fun autoStartServiceIfReady() {
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val networkOk = Build.VERSION.SDK_INT < 37 || checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
        if (notifOk && networkOk) {
            startForegroundService(Intent(this, LanService::class.java))
        }
    }
    @Deprecated("Activity result bridge required by Flutter embedding")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode == 30) {
            val pin = consentPeer; consentPeer = null
            if(resultCode == Activity.RESULT_OK && data != null && pin != null) {
                startForegroundService(Intent(this,LanService::class.java).setAction("CAPTURE").putExtra("pin",pin).putExtra("consent",data).putExtra("result",resultCode))
            } else { engine.stopSharing(); engine.journal.add("Child declined system screen capture consent") }
        }
    }
}
