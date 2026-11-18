package com.jacekgajek.kapelusz.server.admin

import com.jacekgajek.kapelusz.server.HttpResponse
import java.security.SecureRandom
import java.util.Base64

/**
 * The admin panel's one page: `admin/index.html` from the jar, with the way to sign in put in.
 *
 * The page's script and styles are inline, so each answer carries a fresh nonce: the browser runs only the script
 * that has it. Google's sign-in script is the only thing loaded from elsewhere.
 */
internal class AdminPage(googleClientId: String?) {
    /** Put into the page's HTML, so only what a client id is made of gets through. */
    private val clientId = googleClientId.orEmpty().also { require(CLIENT_ID_SHAPE.matches(it)) { "Not a Google client id" } }

    /** How the page signs in: with Google, with the local server's development users, or not at all. */
    private val signIn = when {
        googleClientId == null -> "dev"
        googleClientId.isEmpty() -> "none"
        else -> "google"
    }
    private val template: String = checkNotNull(AdminPage::class.java.getResource(RESOURCE)) { "$RESOURCE is not in the jar" }.readText()
    private val random = SecureRandom()

    fun response(): HttpResponse {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
        val html = template.replace(NONCE, nonce).replace(CLIENT_ID, clientId).replace(SIGN_IN, signIn)
        return HttpResponse(200, html, headers(nonce))
    }

    private fun headers(nonce: String) = mapOf(
        "content-type" to "text/html; charset=utf-8",
        "content-security-policy" to listOf(
            "default-src 'none'",
            "script-src 'nonce-$nonce' $GOOGLE/client",
            "style-src 'nonce-$nonce' $GOOGLE/style",
            "frame-src $GOOGLE/",
            "connect-src 'self' $GOOGLE/",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; "),
        // Google's sign-in opens a window that must be able to answer this page.
        "cross-origin-opener-policy" to "same-origin-allow-popups",
        "referrer-policy" to "no-referrer",
    )

    private companion object {
        const val RESOURCE = "/admin/index.html"
        const val NONCE = "{{NONCE}}"
        const val CLIENT_ID = "{{CLIENT_ID}}"
        const val SIGN_IN = "{{SIGN_IN}}"
        const val NONCE_BYTES = 16
        val CLIENT_ID_SHAPE = Regex("[A-Za-z0-9._-]*")
        const val GOOGLE = "https://accounts.google.com/gsi"
    }
}
