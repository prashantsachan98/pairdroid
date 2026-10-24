package org.pairdroid.pairdroid

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

class PolicyAdmin : DeviceAdminReceiver()

class Journal(context: Context) : SQLiteOpenHelper(context, "local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, event TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    @Synchronized fun add(event: String) {
        writableDatabase.execSQL("INSERT INTO audit(at,event) VALUES(?,?)", arrayOf(System.currentTimeMillis(), event.take(300)))
        writableDatabase.execSQL("DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT 200)")
    }
    fun list(): JSONArray = JSONArray().also { out -> readableDatabase.rawQuery("SELECT at,event FROM audit ORDER BY id DESC LIMIT 50", null).use { c ->
        while(c.moveToNext()) out.put(JSONObject().put("at", c.getLong(0)).put("event", c.getString(1)))
    } }
}

class DeviceData(private val context: Context, private val vault: Vault, private val journal: Journal) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, PolicyAdmin::class.java)
    fun owner() = dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
    fun usageAllowed(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    fun status(): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT).put("wifi", wifi).put("usageAccess", usageAllowed())
            .put("owner", owner()).put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("battery", battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1)
            .put("at", System.currentTimeMillis()).put("policy", vault.read().optJSONObject("policy") ?: JSONObject())
            .put("suspended", vault.read().optJSONArray("suspended") ?: JSONArray())
    }
    fun apps(): JSONArray {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return JSONArray().also { out ->
            pm.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }.take(500).forEach {
                val drawable = it.loadIcon(pm)
                val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 48, 48); drawable.draw(Canvas(bitmap))
                val bytes = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
                out.put(JSONObject().put("package", it.activityInfo.packageName).put("label", it.loadLabel(pm).toString())
                    .put("icon", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
            }
        }
    }
    /** Replay activity transitions across seven calendar days; overlapping activities count once per package. */
    fun usage(dayCount: Int = 7): JSONArray {
        check(usageAllowed()) { "Grant Usage access on the child device" }
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val first = today.minusDays((dayCount-1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        // Seed activity state with one preceding day; event retention is controlled by Android.
        val events = manager.queryEvents(first - 86400000L, now)
        val active = mutableMapOf<String, Long>()
        val days = Array(dayCount) { mutableMapOf<String, Long>() }
        val last = mutableMapOf<String, Long>()
        fun accumulate(pkg: String, from: Long, to: Long) {
            for (i in 0 until dayCount) {
                val start = today.minusDays((dayCount-1-i).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = today.minusDays((dayCount-2-i).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                val duration = (minOf(to, end) - maxOf(from, start)).coerceAtLeast(0)
                if (duration > 0) days[i][pkg] = (days[i][pkg] ?: 0) + duration
            }
        }
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when(e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { active.putIfAbsent(e.packageName, e.timeStamp); last[e.packageName] = e.timeStamp }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove(e.packageName)?.let { accumulate(e.packageName, it, e.timeStamp) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> { active.forEach { (pkg, from) -> accumulate(pkg, from, e.timeStamp) }; active.clear() }
            }
        }
        active.forEach { (pkg, from) -> accumulate(pkg, from, now) }
        return JSONArray().also { out -> days.forEachIndexed { i, map ->
            val items = JSONArray()
            map.entries.sortedByDescending { it.value }.forEach { (pkg, ms) -> items.put(JSONObject().put("package", pkg).put("ms", ms).put("last", last[pkg] ?: 0)) }
            out.put(JSONObject().put("date", today.minusDays((dayCount-1-i).toLong()).toString()).put("apps", items))
        } }
    }
    @Synchronized fun savePolicy(policy: JSONObject) {
        val limits = policy.getJSONObject("limits")
        require(limits.length() <= 500)
        limits.keys().forEach { pkg -> require(pkg.matches(Regex("[A-Za-z0-9_.]+")) && limits.getInt(pkg) in 0..1440) }
        require(policy.getInt("start") in 0..1439 && policy.getInt("end") in 0..1439)
        val allowed = policy.getJSONArray("allowed"); require(allowed.length() <= 500)
        repeat(allowed.length()) { require(allowed.getString(it).matches(Regex("[A-Za-z0-9_.]+"))) }
        // Build a canonical document, discarding extraneous fields and bounding payloads.
        val clean = JSONObject().put("limits", limits).put("start", policy.getInt("start")).put("end", policy.getInt("end"))
            .put("allowed", allowed).put("updatedAt", System.currentTimeMillis())
        vault.update { it.put("policy", clean) }; journal.add("Policy updated")
        enforce()
    }
    @Synchronized fun clearPolicy() {
        val suspended = vault.read().optJSONArray("suspended") ?: JSONArray()
        val remaining = mutableListOf<String>()
        for(i in 0 until suspended.length()) {
            val pkg = suspended.getString(i)
            if(!owner() || runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false).isNotEmpty() }.getOrDefault(true)) remaining.add(pkg)
        }
        vault.update { it.remove("policy"); if(remaining.isEmpty()) it.remove("suspended") else it.put("suspended", JSONArray(remaining)) }
        check(remaining.isEmpty()) { "Policy disabled, but Android did not release ${remaining.size} packages. Retry Clear local policy or review managed-device settings." }
    }
    private var lastAlert = ""
    @Synchronized fun enforce() {
        val state = vault.read(); val policy = state.optJSONObject("policy") ?: return
        if (!usageAllowed()) return // Keep existing OS suspension; report permission loss in status.
        val daily = usage(1).getJSONObject(0).getJSONArray("apps")
        val times = mutableMapOf<String, Long>()
        repeat(daily.length()) { val item = daily.getJSONObject(it); times[item.getString("package")] = item.getLong("ms") }
        val allowedJson = policy.getJSONArray("allowed")
        val allowed = (0 until allowedJson.length()).map { allowedJson.getString(it) }.toMutableSet()
        allowed.add(context.packageName); allowed.add("com.android.settings")
        val pm = context.packageManager
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName?.let { allowed.add(it) }
        context.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage?.let { allowed.add(it) }
        val minute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val limits = policy.getJSONObject("limits")
        val candidates = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        val blocked = candidates.filter { Rules.blocked(times[it] ?: 0, limits.optInt(it, 0), minute, policy.getInt("start"), policy.getInt("end"), it in allowed) }.toSet()
        if(owner()) {
            val oldJson = state.optJSONArray("suspended") ?: JSONArray()
            val old = (0 until oldJson.length()).map { oldJson.getString(it) }.toSet()
            val applied = old.toMutableSet()
            (old - blocked).forEach { if(dpm.setPackagesSuspended(admin, arrayOf(it), false).isEmpty()) applied.remove(it) }
            (blocked - old).forEach { if(dpm.setPackagesSuspended(admin, arrayOf(it), true).isEmpty()) applied.add(it) }
            if(applied != old) { vault.update { it.put("suspended", JSONArray(applied.toList())) }; journal.add("Suspended package set changed (${applied.size})") }
        }
        val alert = blocked.sorted().joinToString(",")
        if (alert != lastAlert && blocked.isNotEmpty()) {
            journal.add("Usage limit or downtime reached (${blocked.size} apps); ${if(owner()) "managed enforcement" else "advisory"}")
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("alerts", "Usage alerts", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(2, Notification.Builder(context, "alerts").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Screen-time policy active")
                .setContentText(if(owner()) "Managed app restrictions applied" else "Time to take a break. This installation provides advisory limits.").build())
        }
        lastAlert = alert
    }
}
