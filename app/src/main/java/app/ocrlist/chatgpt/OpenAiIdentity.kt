package app.ocrlist.chatgpt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT

object OpenAiIdentity {
    data class Identity(val subject: String, val email: String)

    fun verify(token: String, jwks: String, clientId: String, nonce: String?,
               expectedSubject: String? = null, nowMillis: Long = System.currentTimeMillis()): Identity {
        try {
            val jwt = SignedJWT.parse(token)
            val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) ?: error("Unknown signing key")
            val verified = when {
                jwt.header.algorithm == JWSAlgorithm.RS256 && key is RSAKey -> jwt.verify(RSASSAVerifier(key))
                jwt.header.algorithm == JWSAlgorithm.ES256 && key is ECKey -> jwt.verify(ECDSAVerifier(key))
                else -> false
            }
            require(verified)
            val claims = jwt.jwtClaimsSet
            require(claims.issuer == ChatGptProtocol.ISSUER)
            require(clientId in claims.audience)
            if (claims.audience.size > 1) require(claims.getStringClaim("azp") == clientId)
            require((claims.expirationTime?.time ?: 0) > nowMillis - 5000)
            require((claims.issueTime?.time ?: error("No issue time")) <= nowMillis + 5000)
            claims.notBeforeTime?.let { require(it.time <= nowMillis + 5000) }
            if (nonce != null) require(claims.getStringClaim("nonce") == nonce)
            val subject = claims.subject?.takeIf { it.isNotBlank() } ?: error("No subject")
            if (expectedSubject != null) require(subject == expectedSubject)
            return Identity(subject, claims.getStringClaim("email").orEmpty())
        } catch (_: Exception) {
            throw ChatGptException("OpenAI’s sign-in response could not be verified. Check your device’s time and try again.")
        }
    }
}
