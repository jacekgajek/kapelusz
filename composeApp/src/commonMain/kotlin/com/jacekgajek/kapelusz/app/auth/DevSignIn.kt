package com.jacekgajek.kapelusz.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.data.nowMillis
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Google sign-in, or with `kapelusz.devSignIn` (LOCAL_DEVELOPMENT.md) a sign-in as any made-up user, for the local
 * server, which accepts its unsigned tokens; the real API refuses them.
 */
@Composable
fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    if (AppConfig.DEV_SIGN_IN) DevSignInButton(onIdToken, modifier) else GoogleSignInButton(onIdToken, onError, modifier)
}

// Development builds only, so in English, without string resources.
@Composable
private fun DevSignInButton(onIdToken: (String) -> Unit, modifier: Modifier) {
    var name by remember { mutableStateOf("") }
    val id = devUserId(name)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Test user (local server)") }, singleLine = true)
        OutlinedButton(onClick = { onIdToken(devIdToken(name.trim(), nowMillis())) }, enabled = id != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (id == null) "Sign in" else "Sign in as ${name.trim()}")
        }
    }
}

/** The user id a name signs in as: `dev-` and its letters and digits, so the same name is the same user. */
internal fun devUserId(name: String): String? = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).takeIf { it.isNotEmpty() }?.let { "dev-$it" }

/** An unsigned JWT shaped like Google's ID token, valid for a year, so the app never asks to renew it. */
@OptIn(ExperimentalEncodingApi::class)
internal fun devIdToken(name: String, now: Long): String {
    val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = buildJsonObject {
        put("alg", "none")
        put("typ", "JWT")
    }
    val id = requireNotNull(devUserId(name)) { "No letters or digits in the name" }
    val claims = buildJsonObject {
        put("sub", id)
        put("name", name)
        put("email", "${id.removePrefix("dev-")}@dev.local")
        put("iat", now / 1000)
        put("exp", now / 1000 + YEAR_SECONDS)
    }
    return base64.encode(header.toString().encodeToByteArray()) + "." + base64.encode(claims.toString().encodeToByteArray()) + "."
}

private const val YEAR_SECONDS = 365L * 24 * 3600
