package app.ocrlist

import app.ocrlist.chatgpt.ChatGptException
import app.ocrlist.chatgpt.ChatGptHttp
import app.ocrlist.chatgpt.OpenAiHttpFailure
import app.ocrlist.chatgpt.OpenAiNetworkErrors
import app.ocrlist.chatgpt.OpenAiOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class ChatGptHttpTest {
    private fun builder() = ChatGptHttp.newClientBuilder()
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
    private fun tokenRequest(url: okhttp3.HttpUrl) = Request.Builder().url(url).post(FormBody.Builder()
        .add("grant_type", "authorization_code").add("code", "secret-one-time-code").build()).build()

    @Test fun fallsBackToAnotherAddressWithoutReplayingTokenPost() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""{"accepted":true}"""))
            val dns = object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
            }
            val transport = ChatGptHttp(builder().dns(dns).build())
            try {
                val url = server.url("/token").newBuilder().host("openai-test.invalid").build()
                val json = transport.json(tokenRequest(url), OpenAiOperation.TOKEN_EXCHANGE)
                assertTrue(json.getBoolean("accepted"))
                assertEquals(1, server.requestCount)
                assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().contains("secret-one-time-code"))
            } finally { transport.close() }
        }
    }

    @Test fun neverReplaysTokenIfConnectionDropsAfterRequestWasSent() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"unexpected_replay":true}"""))
            // Supply another route so a replayable POST would have an opportunity to retry.
            val dns = object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.1"))
            }
            val transport = ChatGptHttp(builder().dns(dns).build())
            try {
                val url = server.url("/token").newBuilder().host("openai-test.invalid").build()
                val failure = runCatching { transport.json(tokenRequest(url), OpenAiOperation.TOKEN_EXCHANGE) }.exceptionOrNull()
                assertTrue(failure is ChatGptException)
                assertTrue(failure!!.message!!.contains("Token exchange"))
                assertFalse(failure.message!!.contains("secret-one-time-code"))
                assertEquals(1, server.requestCount)
            } finally { transport.close() }
        }
    }

    @Test fun reportsDnsFailureWithStageWithoutRawExceptionContents() = runBlocking {
        val transport = ChatGptHttp(builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("secret-one-time-code")
        }).build())
        try {
            val failure = runCatching {
                transport.json(tokenRequest(okhttp3.HttpUrl.Builder().scheme("https").host("openai-test.invalid").build()), OpenAiOperation.TOKEN_EXCHANGE)
            }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("Token exchange"))
            assertTrue(failure.message!!.contains("openai-test.invalid; DNS"))
            assertFalse(failure.message!!.contains("secret-one-time-code"))
        } finally { transport.close() }
    }

    @Test fun reportsBodyReadFailureAtItsActualStage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"keys\":[]}").setHeader("Content-Length", "1000")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            val transport = ChatGptHttp(builder().build())
            try {
                val failure = runCatching {
                    transport.json(Request.Builder().url(server.url("/jwks")).build(), OpenAiOperation.SIGNING_KEYS)
                }.exceptionOrNull()!!
                assertTrue(failure is ChatGptException)
                assertTrue(failure.message!!.contains("Identity verification keys"))
            } finally { transport.close() }
        }
    }

    @Test fun preservesHttpFailureStatusAndDoesNotExposeServerBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":"invalid_grant","error_description":"secret-one-time-code"}"""))
            val transport = ChatGptHttp(builder().build())
            try {
                val failure = runCatching { transport.json(tokenRequest(server.url("/token")), OpenAiOperation.TOKEN_EXCHANGE) }.exceptionOrNull()
                assertTrue(failure is OpenAiHttpFailure)
                assertEquals(400, (failure as OpenAiHttpFailure).status)
                assertEquals("invalid_grant", failure.code)
                assertTrue(failure.message!!.contains("fresh sign-in"))
                assertFalse(failure.message!!.contains("secret-one-time-code"))
            } finally { transport.close() }
        }
    }

    @Test fun doesNotFollowTokenRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
            server.enqueue(MockResponse().setBody("{}"))
            val transport = ChatGptHttp(builder().build())
            try {
                val failure = runCatching { transport.json(tokenRequest(server.url("/token")), OpenAiOperation.TOKEN_EXCHANGE) }.exceptionOrNull()
                assertEquals(307, (failure as OpenAiHttpFailure).status)
                assertEquals(1, server.requestCount)
            } finally { transport.close() }
        }
    }

    @Test fun rejectsMalformedJsonWithoutLeakingResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("secret-access-token-not-json"))
            val transport = ChatGptHttp(builder().build())
            try {
                val failure = runCatching { transport.json(Request.Builder().url(server.url("/models")).build(), OpenAiOperation.MODELS) }.exceptionOrNull()!!
                assertTrue(failure.message!!.contains("Model loading"))
                assertFalse(failure.message!!.contains("secret-access-token"))
            } finally { transport.close() }
        }
    }

    @Test fun cancellationClosesNetworkRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = ChatGptHttp(builder().build())
            try {
                val pending = async { transport.json(Request.Builder().url(server.url("/models")).build(), OpenAiOperation.MODELS) }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
            } finally { transport.close() }
        }
    }

    @Test fun tlsAndTimeoutErrorsRemainDistinctAndCredentialSafe() {
        val tls = OpenAiNetworkErrors.describe(OpenAiOperation.TOKEN_REFRESH, "auth.openai.com", SSLHandshakeException("secret-refresh-token"))
        assertTrue(tls.message!!.contains("Session refresh"))
        assertTrue(tls.message!!.contains("TLS handshake"))
        assertFalse(tls.message!!.contains("secret-refresh-token"))
        val timeout = OpenAiNetworkErrors.describe(OpenAiOperation.MODELS, "api.openai.com", SocketTimeoutException("secret"))
        assertTrue(timeout.message!!.contains("Model loading"))
        assertTrue(timeout.message!!.contains("timeout"))
    }

    @Test fun deniedDnsAccessIsNotMisreportedAsBadDnsConfiguration() {
        val denied = UnknownHostException("secret-code").apply {
            initCause(java.io.IOException("getaddrinfo failed: EACCES; secret-code"))
        }
        val failure = OpenAiNetworkErrors.describe(OpenAiOperation.TOKEN_EXCHANGE, "auth.openai.com", denied,
            "app background, Data Saver restricting background data")
        assertTrue(failure.message!!.contains("network access denied"))
        assertTrue(failure.message!!.contains("app background"))
        assertFalse(failure.message!!.contains("secret-code"))
    }

    @Test fun errorDistinguishesForegroundAtRequestStartFromFailureTime() = runBlocking {
        val context = java.util.concurrent.atomic.AtomicReference("app background")
        val transport = ChatGptHttp(builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                context.set("app foreground")
                throw UnknownHostException("secret-code")
            }
        }).build(), networkContext = context::get)
        try {
            val failure = runCatching {
                transport.json(tokenRequest(okhttp3.HttpUrl.Builder().scheme("https").host("openai-test.invalid").build()), OpenAiOperation.TOKEN_EXCHANGE)
            }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("Started: app background; failed: app foreground"))
            assertFalse(failure.message!!.contains("secret-code"))
        } finally { transport.close() }
    }
}
