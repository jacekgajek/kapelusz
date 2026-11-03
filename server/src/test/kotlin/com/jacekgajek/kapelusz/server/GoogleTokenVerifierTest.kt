package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.server.auth.GoogleTokenVerifier
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Date

/** `iat` is in whole seconds. */
private val ISSUED_AT = (System.currentTimeMillis() / 1000 - 60) * 1000

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

class GoogleTokenVerifierTest :
    ExpectSpec({

        expect("accepts valid token") {
            verifier.verify(token()) shouldBe
                VerifiedUser("google-sub-1", "alice@example.com", "Alice", "https://lh3.googleusercontent.com/a/alice=s96-c", issuedAt = ISSUED_AT)
            withClue("without iat: issued before any revoke") { verifier.verify(token(issuedAt = null))?.issuedAt shouldBe 0 }
            verifier.verify(token(issuer = "accounts.google.com"))?.userId shouldBe "google-sub-1"
        }

        expect("rejects foreign, expired, unverified and forged tokens") {
            verifier.verify(token(audience = "someone-else")).shouldBeNull()
            verifier.verify(token(issuer = "https://evil.example.com")).shouldBeNull()
            verifier.verify(token(expiresInSeconds = -600)).shouldBeNull()
            verifier.verify(token(emailVerified = false)).shouldBeNull()
            verifier.verify(token(signingKey = RSAKeyGenerator(2048).keyID("k1").generate())).shouldBeNull()
            verifier.verify("garbage").shouldBeNull()
        }

        expect("the photo comes from an old token of the same user only") {
            val old = token(expiresInSeconds = -30L * 24 * 3600)
            withClue("an expired token does not sign in") { verifier.verify(old).shouldBeNull() }
            withClue("but its photo is still Alice's") {
                verifier.pictureOf(old, "google-sub-1") shouldBe "https://lh3.googleusercontent.com/a/alice=s96-c"
            }
            verifier.pictureOf(old, "someone-else").shouldBeNull()
            withClue("another app's token") { verifier.pictureOf(token(audience = "other-app"), "google-sub-1").shouldBeNull() }
            withClue("not signed by Google") {
                verifier.pictureOf(token(signingKey = RSAKeyGenerator(2048).keyID("k1").generate()), "google-sub-1").shouldBeNull()
            }
        }
    })
