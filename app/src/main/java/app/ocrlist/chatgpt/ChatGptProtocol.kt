package app.ocrlist.chatgpt

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

open class ChatGptException(message: String) : Exception(message)

object ChatGptProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"

    fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(host: String, redirect: String, state: String, nonce: String, verifier: String,
                         clientId: String? = null, email: String? = null): String {
        val params = linkedMapOf(
            "client_id" to (clientId ?: "dynamic_agent_client"), "ext_agent_host_id" to host,
            "response_type" to "code", "redirect_uri" to redirect, "scope" to SCOPE,
            "resource" to RESOURCE, "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to challenge(verifier),
        )
        if (clientId == null) params["agent_name_hint"] = "OCR List"
        if (!email.isNullOrBlank()) params["login_hint"] = email
        return AUTHORIZE + "?" + params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    data class Callback(val code: String?, val clientId: String?, val denied: Boolean)

    // Ignore unrelated localhost requests, including favicon requests and wrong-state probes.
    fun callback(target: String, expectedState: String, previousClientId: String?): Callback? {
        val uri = try { URI(target) } catch (_: Exception) { return null }
        if (uri.isAbsolute || uri.rawPath != CALLBACK_PATH || uri.rawFragment != null) return null
        val params = mutableMapOf<String, String>()
        try {
            for (part in (uri.rawQuery ?: return null).split('&')) {
                val pair = part.split('=', limit = 2)
                val key = URLDecoder.decode(pair[0], "UTF-8")
                val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                if (params.put(key, value) != null) return null
            }
        } catch (_: IllegalArgumentException) { return null }
        val state = params["state"] ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return null
        if (params.containsKey("error")) return Callback(null, null, true)
        val client = params["client_id"] ?: previousClientId ?: return null
        if (!client.startsWith("oaiapp_") || (previousClientId != null && client != previousClientId)) return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return Callback(code, client, false)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
