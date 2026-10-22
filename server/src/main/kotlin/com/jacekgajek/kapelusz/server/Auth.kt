package com.jacekgajek.kapelusz.server

import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

/** [picture]: the Google profile photo, only known when signing in with a Google token (not with a session). */
data class VerifiedUser(val userId: String, val email: String?, val name: String?, val picture: String? = null)

fun interface TokenVerifier {
    /** Returns the user for a valid token, or null for an invalid, expired or foreign one. */
    fun verify(token: String): VerifiedUser?

    /**
     * The photo in a Google sign-in token of [userId] that may have expired (ITERATION_2 §7.4): the app keeps the one
     * from its first sign-in. Signed by Google for this app and this user is enough, since it only sets their own photo.
     */
    fun pictureOf(token: String, userId: String): String? = null
}

/** Verifies Google ID tokens: RS256 signature against Google's keys, issuer, audience, expiry, verified email. */
class GoogleTokenVerifier(
    private val clientIds: Set<String>,
    keys: JWKSource<SecurityContext> = JWKSourceBuilder.create<SecurityContext>(URI(GOOGLE_KEYS).toURL()).build(),
) : TokenVerifier {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(clientIds, JWTClaimsSet.Builder().build(), setOf("sub", "iss", "exp"), null)
    }

    override fun verify(token: String): VerifiedUser? {
        val claims = try {
            processor.process(token, null)
        } catch (e: Exception) {
            return null
        }
        if (claims.issuer !in ISSUERS) return null
        if (claims.getBooleanClaim("email_verified") == false) return null
        return VerifiedUser(claims.subject, claims.getStringClaim("email"), claims.getStringClaim("name"), claims.getStringClaim("picture"))
    }

    // As above, but an expired token still counts (only for reading the photo, see pictureOf).
    private val anyAgeProcessor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
        jwtClaimsSetVerifier = JWTClaimsSetVerifier<SecurityContext> { claims, _ ->
            if (claims.audience.none { it in clientIds } || claims.issuer !in ISSUERS || claims.subject == null) throw BadJWTException("Not a token of this app")
        }
    }

    override fun pictureOf(token: String, userId: String): String? {
        val claims = try {
            anyAgeProcessor.process(token, null)
        } catch (e: Exception) {
            return null
        }
        return claims.getStringClaim("picture")?.takeIf { claims.subject == userId }
    }

    /** Fetches Google's signing keys once, e.g. while priming a SnapStart snapshot. */
    fun warmUp() {
        verify("not-a-token")
    }

    companion object {
        const val GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs"
        val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
