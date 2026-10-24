package org.pairdroid.pairdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/** Actual Android Keystore + TLS listener, not a mocked protocol implementation. */
@RunWith(AndroidJUnit4::class)
class LanSecurityTest {
    @Test fun enrollmentAuthorizationRevocationAndPolicyRoundTrip() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LanEngine.get(ctx)
        val client = Vault(ctx,"test-client")
        val other = Vault(ctx,"test-unpaired")
        engine.revoke(); engine.start()
        val address = engine.ownAddresses().firstOrNull() ?: error("No local IPv4 interface")
        var port = engine.state().getInt("port")
        fun request(who: Vault, cmd: String, extra: JSONObject = JSONObject(), pin: String = engine.vault.fingerprint): JSONObject {
            val socket = who.tls(pin).socketFactory.createSocket(address,port) as SSLSocket
            return socket.use {
                it.soTimeout = 5000; it.startHandshake()
                it.outputStream.write((JSONObject(extra.toString()).put("v",1).put("cmd",cmd).toString()+"\n").toByteArray())
                it.outputStream.flush(); JSONObject(Rules.readLine(it.inputStream,8*1024*1024))
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFalse(request(client,"status").getBoolean("ok"))
            assertFalse(request(client,"apps").getBoolean("ok"))
            assertFalse(request(client,"policy",JSONObject().put("policy",JSONObject())).getBoolean("ok"))
            assertThrows(Exception::class.java) { request(client,"status",pin="0".repeat(64)) }
            fun pendingPair(approve: Boolean): JSONObject {
                engine.openPairing()
                val code = engine.state().getString("code")
                val future = executor.submit<JSONObject> { request(client,"pair",JSONObject().put("code",code).put("name","Test parent")) }
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
                while(engine.state().isNull("pending") && System.nanoTime() < deadline) Thread.sleep(20)
                assertFalse("Parent must not be authorized before approval",engine.vault.read().optString("parent").isNotEmpty())
                assertFalse("Pair request must wait for child approval",future.isDone)
                engine.approve(client.fingerprint,approve)
                return future.get(5,TimeUnit.SECONDS)
            }
            assertFalse(pendingPair(false).getBoolean("ok"))
            assertTrue(pendingPair(true).getBoolean("ok"))
            assertTrue(request(client,"status").getBoolean("ok"))
            assertTrue(request(client,"apps").getJSONObject("data").getJSONArray("apps").length() > 0)
            if(!engine.data.usageAllowed()) assertFalse(request(client,"usage").getBoolean("ok"))
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set ${ctx.packageName} GET_USAGE_STATS allow").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            assertEquals(7,request(client,"usage").getJSONObject("data").getJSONArray("days").length())
            assertFalse(request(other,"status").getBoolean("ok"))
            assertFalse(request(client,"frame").getBoolean("ok"))
            assertTrue(request(client,"shareRequest").getBoolean("ok"))
            assertFalse("Remote request is not capture consent",engine.capture.active)
            assertFalse(request(client,"frame").getBoolean("ok"))
            engine.stopSharing()
            val policy = JSONObject().put("limits",JSONObject().put("org.example.game",15)).put("start",1320).put("end",420).put("allowed",org.json.JSONArray())
            assertTrue(request(client,"policy",JSONObject().put("policy",policy)).getBoolean("ok"))
            assertEquals(15,request(client,"status").getJSONObject("data").getJSONObject("policy").getJSONObject("limits").getInt("org.example.game"))
            engine.stop(); engine.start()
            port = engine.state().getInt("port")
            assertEquals(15,engine.vault.read().getJSONObject("policy").getJSONObject("limits").getInt("org.example.game"))
            // Listener port is intentionally dynamic: status uses the new current port below.
            engine.revoke()
            assertEquals("",engine.vault.read().optString("parent"))
            assertFalse(engine.vault.read().has("policy"))
            engine.openPairing()
            repeat(5) { assertFalse(request(client,"pair",JSONObject().put("code","invalid")).getBoolean("ok")) }
            assertFalse(request(client,"pair",JSONObject().put("code",engine.state().getString("code"))).getBoolean("ok"))
        } finally {executor.shutdownNow(); engine.revoke(); engine.stop()}
    }
}
