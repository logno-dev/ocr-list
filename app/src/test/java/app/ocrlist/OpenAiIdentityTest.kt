package app.ocrlist

import app.ocrlist.chatgpt.ChatGptException
import app.ocrlist.chatgpt.ChatGptProtocol
import app.ocrlist.chatgpt.OpenAiIdentity
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class OpenAiIdentityTest {
    companion object {
        private val key = RSAKeyGenerator(2048).keyID("test-key").generate()
        private val jwks = JWKSet(key.toPublicJWK()).toString()
        private const val NOW = 1_800_000_000_000L
    }
    private fun token(issuer: String = ChatGptProtocol.ISSUER, audience: String = "oaiapp_test",
                      nonce: String = "nonce", expires: Long = NOW + 60_000, subject: String = "user-1"): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
            .issueTime(Date(NOW)).expirationTime(Date(expires)).claim("nonce", nonce).claim("email", "test@example.com").build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
            .apply { sign(RSASSASigner(key)) }.serialize()
    }
    private fun verify(token: String, keys: String = jwks) = OpenAiIdentity.verify(token, keys, "oaiapp_test", "nonce", "user-1", NOW)

    @Test fun acceptsVerifiedIdentity() { assertEquals("test@example.com", verify(token()).email) }
    @Test fun rejectsWrongIssuerAudienceNonceExpiryAndSubject() {
        for (token in listOf(token(issuer = "https://example.com"), token(audience = "oaiapp_other"),
            token(nonce = "other"), token(expires = NOW - 60_000), token(subject = "user-2"))) {
            assertThrows(ChatGptException::class.java) { verify(token) }
        }
    }
    @Test fun rejectsInvalidSignature() {
        val differentKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        assertThrows(ChatGptException::class.java) { verify(token(), JWKSet(differentKey.toPublicJWK()).toString()) }
    }
    @Test fun rejectsUnsignedJwt() {
        val unsigned = token().split('.').take(2).joinToString(".") + "."
        assertThrows(ChatGptException::class.java) { verify(unsigned) }
    }
}
