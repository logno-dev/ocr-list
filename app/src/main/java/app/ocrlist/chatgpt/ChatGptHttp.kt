package app.ocrlist.chatgpt

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

enum class OpenAiOperation(val label: String) {
    DISCOVERY("Sign-in configuration"), SIGNING_KEYS("Identity verification keys"),
    TOKEN_EXCHANGE("Token exchange"), TOKEN_REFRESH("Session refresh"),
    MODELS("Model loading"), TRANSCRIPTION("Photo recognition"), SIGN_OUT("Sign-out"),
}

class OpenAiHttpFailure(val status: Int, val code: String, operation: OpenAiOperation) : ChatGptException(
    "${operation.label} failed (HTTP $status). " + when {
        operation == OpenAiOperation.TOKEN_EXCHANGE && code == "invalid_grant" ->
            "The sign-in code expired or was already used. Start a fresh sign-in."
        operation == OpenAiOperation.TOKEN_EXCHANGE && status == 400 ->
            "OpenAI did not accept the sign-in request. Try again; ChatGPT plan access must be available for your account."
        status in 300..399 -> "OpenAI returned an unexpected redirect. Try again on another network."
        else -> ChatGptResponses.errorMessage(code, status)
    }
)

object OpenAiNetworkErrors {
    // Do not display raw exception messages: they can contain URLs or credential-bearing request data.
    fun describe(operation: OpenAiOperation, host: String, error: IOException, networkContext: String = ""): ChatGptException {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val (kind, advice) = when {
            causes.any { it.message?.contains(Regex("\\b(?:EACCES|EPERM)\\b")) == true } -> "network access denied" to
                "Android denied this connection. Keep OCR List open while connecting and check its per-app network restrictions."
            causes.any { it is UnknownHostException } -> "DNS" to
                "Could not resolve the server address. Try Wi-Fi/mobile data or check Private DNS and VPN settings."
            causes.any { it is SSLPeerUnverifiedException } -> "TLS certificate" to
                "Could not verify the server certificate. Check your device’s date/time and any VPN or HTTPS filtering."
            causes.any { it is SSLException } -> "TLS handshake" to
                "Could not establish a secure connection. Try another network and check your device’s date/time or VPN."
            causes.any { it is InterruptedIOException } -> "timeout" to
                "The connection timed out. Try again on Wi-Fi or mobile data."
            causes.any { it is ConnectException || it is NoRouteToHostException } -> "connection" to
                "Could not reach the server. Try another network or check VPN/firewall settings."
            causes.any { it is SocketException } -> "connection closed" to
                "The connection closed unexpectedly. Try again on another network."
            causes.any { it is ProtocolException } -> "HTTP protocol" to
                "The server or network proxy returned an invalid response. Try another network."
            else -> "network I/O" to "The connection was interrupted. Try again on another network."
        }
        val context = networkContext.takeIf { it.isNotBlank() }?.let { " $it." }.orEmpty()
        return ChatGptException("${operation.label} failed [$host; $kind].$context $advice")
    }
}

/** Transport with safe address fallback, but no automatic replay of credential/photo POST bodies. */
class ChatGptHttp(private val http: OkHttpClient = newClientBuilder().build(), private val networkContext: () -> String = { "" }) {
    companion object {
        fun newClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            // Includes trying another DNS address when the first route cannot connect.
            .retryOnConnectionFailure(true)
    }

    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    suspend fun json(request: Request, operation: OpenAiOperation): JSONObject = execute(request, operation) { response ->
        val body = response.body?.string().orEmpty()
        try { JSONObject(body) }
        catch (_: org.json.JSONException) {
            throw ChatGptException("${operation.label} failed: OpenAI returned an unreadable response. Try again.")
        }
    }

    suspend fun <T> execute(request: Request, operation: OpenAiOperation, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val startedWith = connectionContext()
            fun networkError(error: IOException): ChatGptException {
                val failedWith = connectionContext()
                val context = if (failedWith == startedWith) startedWith else "Started: $startedWith; failed: $failedWith"
                return OpenAiNetworkErrors.describe(operation, request.url.host, error, context)
            }
            val safeRequest = request.body?.let { request.newBuilder().method(request.method, OneShotBody(it)).build() } ?: request
            val call = http.newCall(safeRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(networkError(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    val body = it.body?.string().orEmpty()
                                    val error = runCatching { JSONObject(body) }.getOrNull()
                                    val code = error?.optJSONObject("error")?.optString("code") ?: error?.optString("error").orEmpty()
                                    throw OpenAiHttpFailure(it.code, code, operation)
                                }
                                consume(it)
                            }
                        } catch (error: IOException) {
                            // Response-body failures happen after onResponse, not in onFailure.
                            throw networkError(error)
                        }
                    })
                }
            })
        }

    private fun connectionContext() = runCatching { networkContext() }.getOrDefault("network state unavailable")

    fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
