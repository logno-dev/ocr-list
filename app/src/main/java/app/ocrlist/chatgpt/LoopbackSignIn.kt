package app.ocrlist.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Browser callback listener, bound only to this device. It survives Activity recreation. */
class LoopbackSignIn(hostId: String, private val clientId: String?, email: String?) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    private val state = ChatGptProtocol.randomValue()
    val nonce = ChatGptProtocol.randomValue()
    val verifier = ChatGptProtocol.randomValue()
    val redirect = "http://127.0.0.1:${server.localPort}${ChatGptProtocol.CALLBACK_PATH}"
    val url = ChatGptProtocol.authorizationUrl(hostId, redirect, state, nonce, verifier, clientId, email)

    suspend fun awaitCallback(): ChatGptProtocol.Callback = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 10 * 60 * 1_000_000_000L
        while (!server.isClosed && System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 2000
                try {
                    // Bound request headers and read byte-wise to avoid unbounded readLine allocations.
                    val input = socket.getInputStream()
                    val request = StringBuilder()
                    while (request.length < 16_384 && !request.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte == -1) break
                        request.append(byte.toChar())
                    }
                    val lines = request.toString().split("\r\n")
                    val first = lines.first().split(' ')
                    val host = lines.drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    val callback = if (request.endsWith("\r\n\r\n") && first.size == 3 && first[0] == "GET" &&
                        host == "127.0.0.1:${server.localPort}") ChatGptProtocol.callback(first[1], state, clientId) else null
                    val html = if (callback == null) "Invalid callback. Return to OCR List and try signing in again."
                        else "<h2>Return to OCR List</h2><p>Finish connecting your account in the app.</p>" +
                            "<p><a href=\"ocrlist://oauth-complete\">Open OCR List</a></p>"
                    val body = ("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\">" +
                        "<title>OCR List</title></head><body>$html</body></html>").toByteArray()
                    val status = if (callback == null) "400 Bad Request" else "200 OK"
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                            "Content-Security-Policy: default-src 'none'\r\nConnection: close\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n").toByteArray())
                        write(body); flush()
                    }
                    if (callback != null) return@withContext callback
                } catch (_: java.io.IOException) { /* Ignore an incomplete or abandoned browser request. */ }
            }
        }
        throw ChatGptException("Sign-in timed out. Return to OCR List and try again.")
    }

    override fun close() { server.close() }
}
