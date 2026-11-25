package io.nekohasekai.sagernet.generator.warp

import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object WarpRegistration {
    // Do not use a data class: its generated toString would expose the private key.
    class Account(val privateKey: String, val peerPublicKey: String,
                  val ipv4: String, val ipv6: String)

    fun register(request: (String, String, JSONObject?, String?) -> JSONObject): Account {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        // RFC 8410 encodings for X25519 without optional attributes.
        val privatePrefix = byteArrayOf(0x30,0x2e,0x02,0x01,0x00,0x30,0x05,0x06,0x03,0x2b,0x65,0x6e,0x04,0x22,0x04,0x20)
        val publicPrefix = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x6e,0x03,0x21,0x00)
        fun raw(encoded: ByteArray, prefix: ByteArray): ByteArray {
            require(encoded.size == prefix.size + 32 && encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                "Unsupported X25519 key encoding"
            }
            return encoded.copyOfRange(prefix.size, encoded.size)
        }
        val secret = raw(pair.private.encoded, privatePrefix)
        try {
            val pub = Base64.encodeToString(raw(pair.public.encoded, publicPrefix), Base64.NO_WRAP)
            val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            val registration = request("POST", "reg", JSONObject()
                .put("install_id", "").put("tos", tos).put("key", pub)
                .put("fcm_token", "").put("type", "ios").put("locale", "en_US"), null)
                .getJSONObject("result")
            val id = registration.getString("id")
            val token = registration.getString("token")
            require(id.matches(Regex("[A-Za-z0-9_-]+")) && token.isNotBlank())
            val config = request("PATCH", "reg/$id", JSONObject().put("warp_enabled", true), token)
                .getJSONObject("result").getJSONObject("config")
            val peer = config.getJSONArray("peers").getJSONObject(0).getString("public_key")
            require(Base64.decode(peer, Base64.DEFAULT).size == 32)
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            return Account(Base64.encodeToString(secret, Base64.NO_WRAP), peer,
                addresses.getString("v4"), addresses.getString("v6"))
        } finally {
            secret.fill(0)
        }
    }
}
