package app.ocrlist

import app.ocrlist.chatgpt.ChatGptProtocol
import app.ocrlist.chatgpt.LoopbackSignIn
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI

class ChatGptProtocolTest {
    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun usesOwnDynamicRegistrationAndPlanScope() {
        val url = ChatGptProtocol.authorizationUrl("urn:uuid:test", "http://127.0.0.1:1234/auth/callback", "state", "nonce", "verifier")
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertTrue(url.contains("client_id=dynamic_agent_client"))
        assertTrue(url.contains("agent_name_hint=OCR+List"))
        assertTrue(url.contains("chatgpt.tokens.use.direct"))
        assertFalse(url.contains("client_secret"))
        val returning = ChatGptProtocol.authorizationUrl("urn:uuid:test", "http://127.0.0.1:1234/auth/callback", "state", "nonce", "verifier", "oaiapp_existing")
        assertTrue(returning.contains("client_id=oaiapp_existing"))
        assertFalse(returning.contains("agent_name_hint"))
    }

    @Test fun rejectsWrongStateDuplicateFieldsAndAccountSubstitution() {
        val good = "/auth/callback?state=expected&code=test-code&client_id=oaiapp_test"
        assertEquals("test-code", ChatGptProtocol.callback(good, "expected", null)!!.code)
        assertNull(ChatGptProtocol.callback(good, "other", null))
        assertNull(ChatGptProtocol.callback("$good&state=expected", "expected", null))
        assertNull(ChatGptProtocol.callback(good, "expected", "oaiapp_other"))
        assertNull(ChatGptProtocol.callback(good.replace("/auth/callback", "/callback"), "expected", null))
        assertNull(ChatGptProtocol.callback("http://evil.example$good", "expected", null))
        assertNull(ChatGptProtocol.callback("/auth/callback?state=expected&code=x", "expected", null))
        assertEquals("oaiapp_saved", ChatGptProtocol.callback("/auth/callback?state=expected&code=x", "expected", "oaiapp_saved")!!.clientId)
    }

    @Test fun validatesStateEvenWhenAccessIsDenied() {
        assertTrue(ChatGptProtocol.callback("/auth/callback?state=expected&error=access_denied", "expected", null)!!.denied)
        assertNull(ChatGptProtocol.callback("/auth/callback?state=wrong&error=access_denied", "expected", null))
    }

    @Test fun actualLoopbackListenerIgnoresInvalidRequestsAndCompletesOnce() = runBlocking {
        LoopbackSignIn("urn:uuid:test", null, null).use { attempt ->
            val pending = async { attempt.awaitCallback() }
            val state = URI(attempt.url).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
            val port = URI(attempt.redirect).port
            val invalid = async(kotlinx.coroutines.Dispatchers.IO) { request(port, "/favicon.ico") }
            assertTrue(invalid.await().startsWith("HTTP/1.1 400"))
            val response = async(kotlinx.coroutines.Dispatchers.IO) { request(port, "/auth/callback?state=$state&code=one-use&client_id=oaiapp_test") }
            assertTrue(response.await().startsWith("HTTP/1.1 200"))
            assertEquals("one-use", pending.await().code)
        }
    }

    private fun request(port: Int, path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()); flush()
        }
        socket.getInputStream().bufferedReader().readText()
    }
}
