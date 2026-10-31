package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.server.TokenVerifier
import com.jacekgajek.kapelusz.server.VerifiedUser
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Accepts the unsigned tokens of the app's development sign-in (`DevSignIn` in composeApp) instead of Google's:
 * `alg: none`, a `dev-` subject, not expired. Only this server takes them; the real API checks Google's signature.
 */
class DevTokenVerifier(private val clock: () -> Long = System::currentTimeMillis) : TokenVerifier {

    override fun verify(token: String): VerifiedUser? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[2].isNotEmpty()) return null
        val header = decode(parts[0]) ?: return null
        val claims = decode(parts[1]) ?: return null
        if (header["alg"]?.jsonPrimitive?.content != "none") return null
        val subject = claims["sub"]?.jsonPrimitive?.content?.takeIf { SUBJECT.matches(it) } ?: return null
        val expires = claims["exp"]?.jsonPrimitive?.longOrNull ?: return null
        if (expires * 1000 <= clock()) return null
        return VerifiedUser(
            subject,
            claims["email"]?.jsonPrimitive?.content,
            claims["name"]?.jsonPrimitive?.content,
            issuedAt = (claims["iat"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
        )
    }

    private fun decode(part: String): JsonObject? = runCatching {
        KapeluszJson.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()).jsonObject
    }.getOrNull()

    private companion object {
        val SUBJECT = Regex("dev-[a-z0-9-]{1,40}")
    }
}
