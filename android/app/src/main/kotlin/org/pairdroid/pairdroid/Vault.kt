package org.pairdroid.pairdroid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal
import android.util.AtomicFile

/** Keystore-backed identity; encrypted, atomically replaced private state. */
class Vault(private val context: Context, private val identityAlias: String = "identity") {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val file = AtomicFile(java.io.File(context.filesDir, "trust.enc"))
    init {
        if (!store.containsAlias(identityAlias)) {
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(identityAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    // Conscrypt signs TLS's prehashed ECDSA transcript through NONEwithECDSA.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=PairDroid"))
                    .setCertificateNotAfter(java.util.Date(4102444800000L)).build())
            }.generateKeyPair()
        }
        if (!store.containsAlias("storage")) KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    val certificate get() = store.getCertificate(identityAlias) as X509Certificate
    val fingerprint get() = pin(certificate)
    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.openRead().use { it.readBytes() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, store.getKey("storage", null) as SecretKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        // Corrupt state is an error; never silently reset trust.
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun update(action: (JSONObject) -> Unit) {
        val state = read(); action(state)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey("storage", null) as SecretKey)
        val output = file.startWrite()
        try { output.write(cipher.iv + cipher.doFinal(state.toString().toByteArray())); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun tls(expectedPin: String? = null): SSLContext {
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(k: String?, i: Array<out java.security.Principal>?) = arrayOf("identity")
            override fun chooseClientAlias(k: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = "identity"
            override fun getServerAliases(k: String?, i: Array<out java.security.Principal>?) = arrayOf("identity")
            override fun chooseServerAlias(k: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = if (k == "EC") "identity" else null
            override fun getCertificateChain(a: String?) = arrayOf(certificate)
            override fun getPrivateKey(a: String?) = store.getKey(identityAlias, null) as PrivateKey
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) { require(c.isNotEmpty()); c[0].checkValidity() }
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                require(expectedPin != null && c.isNotEmpty() && pin(c[0]) == expectedPin) { "Child fingerprint mismatch" }
                c[0].checkValidity()
            }
        }
        // Unknown client certificates may reach only the bounded enrollment gate.
        // LanEngine independently authorizes the certificate on EVERY command.
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
    }
    companion object {
        fun pin(c: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
    }
}
