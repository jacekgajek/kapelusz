package com.jacekgajek.kapelusz.server

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
        issuedAt: Date? = Date(ISSUED_AT),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject("google-sub-1").issuer(issuer).audience(audience)
            .expirationTime(Date(System.currentTimeMillis() + expiresInSeconds * 1000)).issueTime(issuedAt)
            .claim("email", "alice@example.com").claim("email_verified", emailVerified).claim("name", "Alice")
            .claim("picture", "https://lh3.googleusercontent.com/a/alice=s96-c")
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }
            .serialize()
    }

    @Test
    fun acceptsValidToken() {
        assertEquals(
            VerifiedUser("google-sub-1", "alice@example.com", "Alice", "https://lh3.googleusercontent.com/a/alice=s96-c", issuedAt = ISSUED_AT),
            verifier.verify(token()),
        )
        assertEquals(0, verifier.verify(token(issuedAt = null))?.issuedAt, "without iat: issued before any revoke")
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

    @Test
    fun thePhotoComesFromAnOldTokenOfTheSameUserOnly() {
        val old = token(expiresInSeconds = -30L * 24 * 3600)
        assertNull(verifier.verify(old), "an expired token does not sign in")
        assertEquals("https://lh3.googleusercontent.com/a/alice=s96-c", verifier.pictureOf(old, "google-sub-1"), "but its photo is still Alice's")
        assertNull(verifier.pictureOf(old, "someone-else"))
        assertNull(verifier.pictureOf(token(audience = "other-app"), "google-sub-1"), "another app's token")
        assertNull(verifier.pictureOf(token(signingKey = RSAKeyGenerator(2048).keyID("k1").generate()), "google-sub-1"), "not signed by Google")
    }

    private companion object {
        /** `iat` is in whole seconds. */
        val ISSUED_AT = (System.currentTimeMillis() / 1000 - 60) * 1000
    }
}
