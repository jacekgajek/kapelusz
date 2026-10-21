package com.jacekgajek.artsplit.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleTokenVerifierTest {
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
    private val verifier = GoogleTokenVerifier(setOf("web-client"), ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())))

    private fun token(
        audience: String = "web-client",
        issuer: String = "https://accounts.google.com",
        expiresInSeconds: Long = 3600,
        emailVerified: Boolean = true,
        signingKey: RSAKey = key,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject("google-sub-1").issuer(issuer).audience(audience)
            .expirationTime(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .claim("email", "alice@example.com").claim("email_verified", emailVerified).claim("name", "Alice")
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }
            .serialize()
    }

    @Test
    fun acceptsValidToken() {
        assertEquals(VerifiedUser("google-sub-1", "alice@example.com", "Alice"), verifier.verify(token()))
        assertEquals("google-sub-1", verifier.verify(token(issuer = "accounts.google.com"))?.userId)
    }

    @Test
    fun rejectsForeignExpiredUnverifiedAndForgedTokens() {
        assertNull(verifier.verify(token(audience = "someone-else")))
        assertNull(verifier.verify(token(issuer = "https://evil.example.com")))
        assertNull(verifier.verify(token(expiresInSeconds = -600)))
        assertNull(verifier.verify(token(emailVerified = false)))
        assertNull(verifier.verify(token(signingKey = RSAKeyGenerator(2048).keyID("k1").generate())))
        assertNull(verifier.verify("garbage"))
    }
}
