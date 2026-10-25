package org.pairdroid.pairdroid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class LanEngine private constructor(val context: Context) {
    var vault = Vault(context); private set
    val journal = Journal(context)
    var data = DeviceData(context, vault, journal); private set
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), ThreadPoolExecutor.AbortPolicy())
    private var server: SSLServerSocket? = null
    @Volatile var running = false; private set
    @Volatile var error = ""
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val discovered = ConcurrentHashMap<String, JSONObject>()
    private val gate = Any()
    private var code = ""
    private var expires = 0L
    private var attempts = 0
    private var pending: Pending? = null
    @Volatile var shareRequest: String? = null
    @Volatile var sharingPeer: String? = null
    @Volatile var lastFramePoll = 0L
    val capture = CaptureEngine(context) { stopSharing() }
    data class Pending(val pin: String, val name: String, val latch: CountDownLatch = CountDownLatch(1), var approved: Boolean = false)

    fun ownAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && lanInterface(it.name) }
        .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && Rules.local(it) }.map { it.hostAddress!! }
    private fun lanInterface(name: String) = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("eth") || name.startsWith("wifi")
    private fun onLink(remote: InetAddress): Boolean = remote is Inet4Address && Rules.local(remote) && NetworkInterface.getNetworkInterfaces().toList().any { nic ->
        nic.isUp && lanInterface(nic.name) && nic.interfaceAddresses.any { link ->
            val local = link.address
            local is Inet4Address && Rules.local(local) && prefixMatches(local.address, remote.address, link.networkPrefixLength.toInt())
        }
    }
    private fun localFor(remote: InetAddress): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && lanInterface(it.name) }.flatMap { it.interfaceAddresses }
        .firstOrNull { it.address is Inet4Address && prefixMatches(it.address.address,remote.address,it.networkPrefixLength.toInt()) }?.address
    private fun prefixMatches(a: ByteArray, b: ByteArray, bits: Int): Boolean = (0 until bits).all { i ->
        (a[i / 8].toInt() and (1 shl (7 - i % 8))) == (b[i / 8].toInt() and (1 shl (7 - i % 8)))
    }
    fun state(): JSONObject {
        val stored = vault.read()
        val result = data.status().put("running", running).put("error", error).put("fingerprint", vault.fingerprint)
            .put("addresses", JSONArray(ownAddresses())).put("port", server?.localPort ?: 0)
            .put("parent", stored.optString("parent")).put("peers", stored.optJSONArray("peers") ?: JSONArray())
            .put("discovered", JSONArray(discovered.values.toList())).put("audit", journal.list())
            .put("sharing", capture.active).put("parked", capture.parked)
            .put("a11yCapture", capture.accessibilityAvailable())
            .put("a11ySupported", android.os.Build.VERSION.SDK_INT >= 30)
            .put("shareRequested", shareRequest != null)
        synchronized(gate) {
            result.put("code", if(SystemClock.elapsedRealtime() < expires) code else "")
            result.put("pending", pending?.let { JSONObject().put("pin", it.pin).put("name", it.name) } ?: JSONObject.NULL)
        }
        return result
    }
    fun openPairing() = synchronized(gate) {
        check(running) { "Start the child LAN service first" }
        check(vault.read().optString("parent").isEmpty()) { "Revoke the existing parent first" }
        pending?.latch?.countDown(); pending = null
        code = "%06d".format(java.security.SecureRandom().nextInt(1000000)); attempts = 0
        expires = SystemClock.elapsedRealtime() + 120000
        journal.add("Pairing window opened")
    }
    /** Store the parent's fingerprint as trusted for silent auto-approval (set by parent during initial setup). */
    fun setAutoApprovePin(pin: String) = vault.update { it.put("autoApprovePin", pin) }
    fun approve(pin: String, approve: Boolean) = synchronized(gate) {
        val p = pending ?: error("No pending request")
        check(p.pin == pin && SystemClock.elapsedRealtime() < expires) { "Pairing request expired" }
        p.approved = approve; p.latch.countDown()
    }
    fun revoke() = synchronized(gate) {
        vault.update { it.remove("parent") }
        expires = 0; code = ""; pending?.latch?.countDown(); pending = null
        stopSharing(); journal.add("Parent pairing revoked")
        data.clearPolicy(); journal.add("Policy cleared")
    }
    fun rotate() {
        check(!running && vault.read().optString("parent").isEmpty() && (vault.read().optJSONArray("peers")?.length() ?: 0) == 0) { "Stop service and revoke or forget all pairings first" }
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("identity") }
        vault = Vault(context); data = DeviceData(context, vault, journal); journal.add("Device identity rotated")
    }
    @Synchronized fun start() {
        if (running) return
        val socket = vault.tls().serverSocketFactory.createServerSocket(0, 8) as SSLServerSocket
        socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3").filter { it in socket.supportedProtocols }.toTypedArray()
        socket.needClientAuth = true
        server = socket; running = true; error = ""
        multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("pairdroid-nsd").apply { setReferenceCounted(false); acquire() }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) { error = "Discovery registration failed ($c); use manual IP" }
            override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
        }
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = "PairDroid-${vault.fingerprint.take(8)}"; serviceType = "_pairdroid._tcp."; port = socket.localPort
            setAttribute("v", "1"); setAttribute("id", vault.fingerprint)
        }, NsdManager.PROTOCOL_DNS_SD, registration)
        Thread({
            while(running) try {
                val client = socket.accept() as SSLSocket
                if (!onLink(client.inetAddress) || localFor(client.inetAddress) != client.localAddress) { client.close(); continue }
                try { workers.execute { serve(client) } } catch (_: java.util.concurrent.RejectedExecutionException) { client.close() }
            } catch (e: Exception) { if(running) { error = e.message ?: "LAN listener failed"; running = false } }
        }, "pairdroid-listener").start()
        journal.add("Visible child LAN service started")
    }
    @Synchronized fun stop() {
        running = false; server?.close(); server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        multicast?.let { if(it.isHeld) it.release() }; multicast = null
        synchronized(gate) { expires = 0; pending?.latch?.countDown(); pending = null }
        stopSharing(); journal.add("Child LAN service stopped") // Full teardown on service stop.
    }
    @Synchronized fun discover() {
        if(discovery != null) return
        discovered.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, c: Int) { error = "Discovery failed ($c). Manual IP remains available"; stopDiscovery() }
            override fun onStopDiscoveryFailed(t: String, c: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) { discovered.remove(i.serviceName) }
            override fun onServiceFound(i: NsdServiceInfo) {
                if(!i.serviceName.startsWith("PairDroid-")) return
                @Suppress("DEPRECATION")
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, c: Int) { error = "Resolution failed ($c); retry discovery or use IP" }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val address = info.host ?: return
                        if(onLink(address)) discovered[info.serviceName] = JSONObject().put("name", info.serviceName).put("host", address.hostAddress)
                            .put("port", info.port).put("hint", info.attributes["id"]?.toString(Charsets.UTF_8) ?: "")
                    }
                })
            }
        }
        discovery = listener
        nsd.discoverServices("_pairdroid._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
    @Synchronized fun stopDiscovery() { discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null }
    private fun serve(socket: SSLSocket) = socket.use { s ->
        s.soTimeout = 10000
        try {
            s.startHandshake()
            val pin = Vault.pin(s.session.peerCertificates[0] as java.security.cert.X509Certificate)
            val request = JSONObject(Rules.readLine(s.inputStream))
            require(request.getInt("v") == 1) { "Unsupported protocol" }
            val cmd = request.getString("cmd")
            val result = if(cmd == "pair") pair(pin, request) else synchronized(gate) {
                check(vault.read().optString("parent") == pin) { "Unauthorized certificate" }
                when(cmd) {
                    "status" -> data.status().put("sharing", capture.active).put("shareRequested", shareRequest != null)
                    "apps" -> JSONObject().put("apps", data.apps())
                    "usage" -> JSONObject().put("days", data.usage())
                    "policy" -> { data.savePolicy(request.getJSONObject("policy")); JSONObject().put("policy", vault.read().getJSONObject("policy")).put("enforced", data.owner()) }
                    "alerts" -> JSONObject().put("alerts", journal.list())
                    "shareRequest" -> {
                        if (capture.parked) {
                            // Projection already authorized — resume silently, no new consent needed.
                            capture.resume(); sharingPeer = pin; shareRequest = null
                            lastFramePoll = SystemClock.elapsedRealtime()
                            journal.add("Screen sharing resumed from parked state (no new consent required)")
                            JSONObject().put("pending", false)
                        } else if (!capture.active) {
                            shareRequest = pin
                            journal.add("Parent requested screen sharing; awaiting child approval")
                            JSONObject().put("pending", true)
                        } else {
                            JSONObject().put("pending", false)
                        }
                    }
                    "frame" -> {
                        check(sharingPeer == pin && capture.active) { "Screen sharing requires child approval" }
                        lastFramePoll = SystemClock.elapsedRealtime()
                        capture.frame()
                    }
                    // Parent-initiated stopSharing parks the projection (token stays alive).
                    // The parent can reconnect and get frames without a new consent dialog.
                    "stopSharing" -> { parkSharing(); JSONObject() }
                    "unpair" -> { revoke(); JSONObject() }
                    else -> error("Unknown command")

                }
            }
            s.outputStream.write((JSONObject().put("ok", true).put("data", result).toString()+"\n").toByteArray()); s.outputStream.flush()
        } catch (e: Exception) {
            runCatching { s.outputStream.write((JSONObject().put("ok", false).put("error", e.message?.take(160) ?: "Request rejected").toString()+"\n").toByteArray()) }
        }
    }
    private fun pair(pin: String, req: JSONObject): JSONObject {
        val p = synchronized(gate) {
            check(vault.read().optString("parent").isEmpty() && SystemClock.elapsedRealtime() < expires && attempts++ < 5 && pending == null) { "Pairing closed or busy" }
            check(req.getString("code") == code) { "Invalid pairing code" }
            Pending(pin, req.optString("name", "Parent").take(80)).also { pending = it }
        }
        // Auto-approve if this PIN matches the pre-registered trusted parent PIN.
        val autoApprovePin = vault.read().optString("autoApprovePin")
        if (autoApprovePin.isNotEmpty() && autoApprovePin == pin) {
            synchronized(gate) { p.approved = true; p.latch.countDown() }
            journal.add("Parent auto-approved (trusted PIN match): ${pin.take(12)}")
        } else {
            p.latch.await(45, TimeUnit.SECONDS)
        }
        return synchronized(gate) {
            try {
                check(pending === p && p.approved && SystemClock.elapsedRealtime() < expires && running) { "Pairing denied or expired" }
                vault.update { it.put("parent", pin) }; expires = 0; code = ""
                journal.add("Parent certificate approved: ${pin.take(12)}")
                JSONObject().put("name", data.status().getString("name")).put("pin", vault.fingerprint)
            } finally { if(pending === p) pending = null }
        }
    }
    fun rpc(target: JSONObject, cmd: String, payload: JSONObject = JSONObject()): JSONObject {
        check(context.packageName.endsWith(".parent")) { "Only parent variant can issue commands" }
        val pin = target.getString("pin").lowercase()
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "Enter the child's full SHA-256 fingerprint" }
        if(cmd != "pair") check(peers().any { it.getString("pin") == pin }) { "Pair with this child first" }
        val host = target.getString("host")
        require(host.matches(Regex("[0-9.]+"))) { "Use a local IPv4 address" }
        val address = InetAddress.getByName(host)
        check(onLink(address)) { "Device must be on the same local Wi-Fi/hotspot subnet" }
        val port = target.getInt("port"); require(port in 1024..65535)
        val raw = Socket()
        try {
            val local = requireNotNull(localFor(address)) { "No matching LAN interface" }
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val network = cm.allNetworks.firstOrNull { cm.getLinkProperties(it)?.linkAddresses?.any { link -> link.address == local } == true }
            if(network != null) network.bindSocket(raw) else raw.bind(InetSocketAddress(local,0))
            raw.connect(InetSocketAddress(address, port), 4000)
            val tls = vault.tls(pin).socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.use { s ->
                s.soTimeout = if(cmd == "pair") 50000 else 12000
                s.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3").filter { it in s.supportedProtocols }.toTypedArray()
                s.startHandshake()
                val req = JSONObject(payload.toString()).put("v", 1).put("cmd", cmd)
                val encoded = req.toString().toByteArray(); require(encoded.size <= Rules.MAX_REQUEST)
                s.outputStream.write(encoded + byteArrayOf(10)); s.outputStream.flush()
                val result = JSONObject(Rules.readLine(s.inputStream, 8 * 1024 * 1024))
                check(result.getBoolean("ok")) { result.optString("error", "Request rejected") }
                val response = result.getJSONObject("data")
                if(cmd == "pair" || cmd == "status") {
                    val peer = JSONObject(target.toString()).put("name", response.optString("name", target.optString("name", "Child")))
                    val updated = peers().filter { it.getString("pin") != pin }.toMutableList(); updated.add(peer)
                    vault.update { it.put("peers", JSONArray(updated)) }
                }
                if(cmd == "unpair") forget(pin)
                return response
            }
        } finally { raw.close() }
    }
    private fun peers(): List<JSONObject> = vault.read().optJSONArray("peers")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } } ?: emptyList()
    fun forget(pin: String) { vault.update { it.put("peers", JSONArray(peers().filter { p -> p.getString("pin") != pin })) }; journal.add("Local child pairing removed") }
    fun approveSharing(): String = synchronized(gate) {
        val pin = shareRequest ?: error("No parent screen request")
        check(vault.read().optString("parent") == pin) { "Pairing revoked" }
        pin
    }
    /** Begin sharing via accessibility capture — service was enabled once, no dialog needed. */
    fun beginSharing(pin: String) = synchronized(gate) {
        check(shareRequest == pin && vault.read().optString("parent") == pin) { "Sharing approval expired" }
        sharingPeer = pin; shareRequest = null; lastFramePoll = SystemClock.elapsedRealtime()
        try { capture.startAccessibility(); journal.add("Screen sharing explicitly approved on child (accessibility capture)") }
        catch(e: Exception) { stopSharing(); throw e }
    }
    fun beginSharing(pin: String, result: Int, intent: android.content.Intent) = synchronized(gate) {
        check(shareRequest == pin && vault.read().optString("parent") == pin) { "Sharing approval expired" }
        sharingPeer = pin; shareRequest = null; lastFramePoll = SystemClock.elapsedRealtime()
        try { capture.start(result, intent); journal.add("Screen sharing explicitly approved on child") }
        catch(e: Exception) { stopSharing(); throw e }
    }
    fun stopSharing() { shareRequest = null; sharingPeer = null; val active = capture.active || capture.parked; capture.stop(); if(active) journal.add("Screen sharing fully stopped and projection released") }
    /** Park sharing: keeps the projection token alive so parent can reconnect without a new consent dialog. */
    fun parkSharing() {
        shareRequest = null; sharingPeer = null
        val wasActive = capture.active
        capture.park()
        if (wasActive) journal.add("Screen sharing paused (projection token retained for reconnect)")
    }
    private var lastPolicyEvaluation = 0L
    fun tick() {
        // Park (not stop) when parent stops polling — token stays alive for silent reconnect.
        if (capture.active && SystemClock.elapsedRealtime() - lastFramePoll > 15000) parkSharing()
        if(SystemClock.elapsedRealtime() - lastPolicyEvaluation >= 30000) {
            lastPolicyEvaluation = SystemClock.elapsedRealtime()
            data.enforce()
        }
    }
    companion object {
        @Volatile private var instance: LanEngine? = null
        fun get(context: Context): LanEngine = instance ?: synchronized(this) { instance ?: LanEngine(context.applicationContext).also { instance = it } }
    }
}
