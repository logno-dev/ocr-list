package app.ocrlist.chatgpt

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

data class ChatGptModel(val id: String, val name: String)
data class ChatGptAccount(val id: String, val label: String, val connected: Boolean)
data class ChatGptState(
    val accounts: List<ChatGptAccount> = emptyList(), val activeId: String = "", val useCloud: Boolean = false,
    val models: List<ChatGptModel> = emptyList(), val model: String = "", val storageError: Boolean = false,
) {
    val active get() = accounts.firstOrNull { it.id == activeId }
    val ready get() = useCloud && active?.connected == true && model.isNotBlank()
}

class ChatGptClient(context: Context) {
    private val store = CredentialStore(context)
    private val mutex = Mutex()
    private var data: JSONObject? = null
    var state = ChatGptState()
        private set
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun initialize() = mutex.withLock {
        try { data = withContext(Dispatchers.IO) { store.load() }; updateState() }
        catch (_: Exception) { state = ChatGptState(storageError = true) }
    }

    private fun document() = data ?: throw ChatGptException("Saved ChatGPT connections could not be opened. Reset connections in recognition settings.")
    private fun accounts(): List<JSONObject> {
        val accounts = document().getJSONArray("accounts")
        return (0 until accounts.length()).map { accounts.getJSONObject(it) }
    }
    private fun active() = accounts().firstOrNull { it.getString("clientId") == document().optString("active") }
        ?: throw ChatGptException("Connect your ChatGPT account in recognition settings first.")

    private fun updateState() {
        val list = accounts()
        val selected = list.firstOrNull { it.getString("clientId") == document().optString("active") }
        val models = selected?.optJSONArray("models") ?: JSONArray()
        state = ChatGptState(
            accounts = list.map { account ->
                val id = account.getString("clientId")
                ChatGptAccount(id, account.optString("email").ifBlank { "ChatGPT account" } + " · " + id.takeLast(6), account.optString("accessToken").isNotBlank())
            },
            activeId = selected?.getString("clientId").orEmpty(), useCloud = document().optBoolean("useCloud"),
            models = (0 until models.length()).map { ChatGptModel(models.getJSONObject(it).getString("id"), models.getJSONObject(it).getString("name")) },
            model = selected?.optString("model").orEmpty(),
        )
    }

    private suspend fun save() {
        withContext(Dispatchers.IO) { store.save(document()) }
        updateState()
    }

    suspend fun beginSignIn(accountId: String? = null): LoopbackSignIn = mutex.withLock {
        val account = accounts().firstOrNull { it.getString("clientId") == accountId }
        withContext(Dispatchers.IO) {
            LoopbackSignIn(document().getString("host"), account?.getString("clientId"), account?.optString("email"))
        }
    }

    suspend fun finishSignIn(attempt: LoopbackSignIn, callback: ChatGptProtocol.Callback) = mutex.withLock {
        if (callback.denied) throw ChatGptException("Sign-in was cancelled or plan access was not granted. Your existing connection was kept.")
        val clientId = callback.clientId ?: throw ChatGptException("Sign-in did not return an app registration.")
        val tokens = json(Request.Builder().url(ChatGptProtocol.TOKEN).post(form(
            "grant_type" to "authorization_code", "client_id" to clientId, "code" to callback.code!!,
            "code_verifier" to attempt.verifier, "redirect_uri" to attempt.redirect, "resource" to ChatGptProtocol.RESOURCE,
        )).build())
        requirePlanAccess(tokens.optString("scope"))
        val old = accounts().firstOrNull { it.getString("clientId") == clientId }
        val identity = verifyIdentity(tokens.getString("id_token"), clientId, attempt.nonce, old?.getString("subject"))
        val account = old ?: JSONObject().put("clientId", clientId).put("models", JSONArray())
        account.put("subject", identity.subject).put("email", identity.email)
        applyTokens(account, tokens)
        if (old == null) document().getJSONArray("accounts").put(account)
        document().put("active", clientId).put("useCloud", true)
        save()
    }

    suspend fun useOffline() = mutex.withLock { document().put("useCloud", false); save() }
    suspend fun selectAccount(id: String) = mutex.withLock {
        require(accounts().any { it.getString("clientId") == id })
        document().put("active", id).put("useCloud", true); save()
    }
    suspend fun selectModel(id: String) = mutex.withLock {
        require(state.models.any { it.id == id })
        active().put("model", id); save()
    }

    suspend fun refreshModels() = mutex.withLock {
        val account = active()
        val token = accessToken(account)
        val response = json(Request.Builder().url("${ChatGptProtocol.RESOURCE}/models").header("Authorization", "Bearer $token").build())
        val source = response.getJSONArray("models")
        val choices = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.getJSONObject(index)
            if (item.optString("visibility") != "list") continue
            val modalities = item.optJSONArray("input_modalities")
            if (modalities != null && (0 until modalities.length()).none { modalities.optString(it) == "image" }) continue
            choices.put(JSONObject().put("id", item.getString("slug")).put("name", item.optString("display_name").ifBlank { item.getString("slug") }))
        }
        account.put("models", choices)
        if ((0 until choices.length()).none { choices.getJSONObject(it).getString("id") == account.optString("model") }) {
            account.put("model", if (choices.length() > 0) choices.getJSONObject(0).getString("id") else "")
        }
        save()
        if (choices.length() == 0) throw ChatGptException("No available models were returned for this account. Check your ChatGPT plan and app access.")
    }

    suspend fun transcribe(bitmap: Bitmap): List<app.ocrlist.ListItem> = mutex.withLock {
        if (!state.ready) throw ChatGptException("Finish connecting ChatGPT and select a model in recognition settings.")
        val token = accessToken(active())
        val image = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                Base64.getEncoder().encodeToString(output.toByteArray())
            }
        }
        val payload = ChatGptResponses.request(state.model, image).toString()
        val request = Request.Builder().url("${ChatGptProtocol.RESOURCE}/responses")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        val text = execute(request) { response ->
            ChatGptResponses.readStream(response.body?.charStream()?.buffered()
                ?: throw ChatGptException("ChatGPT returned an empty response."))
        }
        ChatGptResponses.parseItems(text)
    }

    // Serializing all account operations also serializes rotating-token refreshes.
    private suspend fun accessToken(account: JSONObject): String {
        if (account.optString("accessToken").isBlank()) throw ChatGptException("Sign in to this ChatGPT account again.")
        if (account.optLong("expiresAt") > System.currentTimeMillis() + 60_000) return account.getString("accessToken")
        val tokens = try {
            json(Request.Builder().url(ChatGptProtocol.TOKEN).post(form(
                "grant_type" to "refresh_token", "client_id" to account.getString("clientId"),
                "refresh_token" to account.getString("refreshToken"), "resource" to ChatGptProtocol.RESOURCE,
            )).build())
        } catch (error: HttpFailure) {
            if (error.code == "invalid_grant" || error.status == 401) { clearTokens(account); save() }
            throw error
        }
        if (tokens.has("scope")) requirePlanAccess(tokens.getString("scope"))
        if (tokens.has("id_token")) verifyIdentity(tokens.getString("id_token"), account.getString("clientId"), null, account.getString("subject"))
        applyTokens(account, tokens)
        save()
        return account.getString("accessToken")
    }

    private fun requirePlanAccess(scope: String) {
        if (!scope.split(' ').containsAll(listOf("chatgpt.tokens.use.direct", "resource.invoke"))) {
            throw ChatGptException("Sign-in succeeded, but ChatGPT plan access was not granted. Enable it for OCR List in ChatGPT Settings → Usage.")
        }
    }

    private fun applyTokens(account: JSONObject, tokens: JSONObject) {
        require(tokens.optString("token_type").equals("Bearer", ignoreCase = true))
        require(tokens.getString("access_token").isNotBlank())
        val expires = tokens.getLong("expires_in")
        require(expires in 1..2_592_000)
        account.put("accessToken", tokens.getString("access_token")).put("expiresAt", System.currentTimeMillis() + expires * 1000)
        if (tokens.has("refresh_token")) account.put("refreshToken", tokens.getString("refresh_token"))
        require(account.optString("refreshToken").isNotBlank())
        if (tokens.has("id_token")) account.put("idToken", tokens.getString("id_token"))
        if (tokens.has("scope")) account.put("scope", tokens.getString("scope"))
    }

    private suspend fun verifyIdentity(token: String, client: String, nonce: String?, subject: String?): OpenAiIdentity.Identity {
        val config = discovery()
        // Fresh JWKS for each login/refresh validation also handles signing-key rotation.
        val keys = json(Request.Builder().url(trustedAuthUrl(config.getString("jwks_uri"))).build())
        return OpenAiIdentity.verify(token, keys.toString(), client, nonce, subject)
    }

    /** Returns false when local sign-out succeeded but remote revocation could not be confirmed. */
    suspend fun signOut(): Boolean = mutex.withLock {
        val account = active()
        var revoked = account.optString("refreshToken").isBlank()
        if (!revoked) {
            for (retry in 0..1) {
                try {
                    val endpoint = trustedAuthUrl(discovery().getString("revocation_endpoint"))
                    execute(Request.Builder().url(endpoint).post(form("token" to account.getString("refreshToken"),
                        "token_type_hint" to "refresh_token", "client_id" to account.getString("clientId"))).build()) { }
                    revoked = true; break
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { if (retry == 0) delay(500) }
            }
        }
        clearTokens(account)
        document().put("useCloud", false)
        save()
        revoked
    }

    suspend fun resetUnreadableStore() = mutex.withLock {
        check(state.storageError)
        withContext(Dispatchers.IO) { store.reset(); data = store.load() }
        updateState()
    }

    private fun clearTokens(account: JSONObject) {
        listOf("accessToken", "refreshToken", "idToken", "expiresAt", "scope").forEach { account.remove(it) }
    }
    private suspend fun discovery(): JSONObject = json(Request.Builder().url("${ChatGptProtocol.ISSUER}/.well-known/openid-configuration").build()).also {
        if (it.getString("issuer") != ChatGptProtocol.ISSUER) throw ChatGptException("OpenAI’s identity service could not be verified.")
    }
    private fun trustedAuthUrl(value: String): String {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host != "auth.openai.com" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) {
            throw ChatGptException("OpenAI returned an unexpected identity endpoint.")
        }
        return value
    }
    private fun form(vararg values: Pair<String, String>) = FormBody.Builder().apply { values.forEach { add(it.first, it.second) } }.build()
    private suspend fun json(request: Request): JSONObject = execute(request) { JSONObject(it.body?.string().orEmpty()) }

    private class HttpFailure(val status: Int, val code: String) : ChatGptException(ChatGptResponses.errorMessage(code, status))

    private suspend fun <T> execute(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(ChatGptException("Could not connect to OpenAI. Check your internet connection and try again.")))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        if (!it.isSuccessful) {
                            val error = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                            val code = error?.optJSONObject("error")?.optString("code") ?: error?.optString("error").orEmpty()
                            throw HttpFailure(it.code, code)
                        }
                        consume(it)
                    }
                })
            }
        })
    }

    fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
