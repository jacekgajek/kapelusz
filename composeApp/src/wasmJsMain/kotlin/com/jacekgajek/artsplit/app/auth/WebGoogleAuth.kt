package com.jacekgajek.artsplit.app.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.AppConfig
import com.jacekgajek.artsplit.app.ui.components.language
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.js.Promise

// Google Identity Services (https://accounts.google.com/gsi/client). The button is a real DOM element placed over the
// Compose canvas, because Google requires its own rendered button for the sign-in flow.

private fun gisInit(clientId: String): Promise<JsAny?> = js(
    """(globalThis.artSplitGis ??= new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://accounts.google.com/gsi/client';
        script.async = true;
        script.onerror = () => reject(new Error('Could not load Google sign-in'));
        script.onload = () => {
            google.accounts.id.initialize({
                client_id: clientId,
                auto_select: true,
                use_fedcm_for_prompt: true,
                callback: (response) => { if (globalThis.artSplitOnCredential) globalThis.artSplitOnCredential(response.credential); },
            });
            resolve(null);
        };
        document.head.appendChild(script);
    }))""",
)

private fun setCredentialCallback(callback: (String) -> Unit): Unit = js("{ globalThis.artSplitOnCredential = callback; }")

private fun showButton(left: Double, top: Double, width: Double, locale: String): Unit = js(
    """{
        let host = document.getElementById('art-split-google-button');
        if (!host) {
            host = document.createElement('div');
            host.id = 'art-split-google-button';
            host.style.position = 'fixed';
            host.style.zIndex = '10';
            document.body.appendChild(host);
            google.accounts.id.renderButton(host, { theme: 'filled_blue', size: 'large', text: 'signin_with', shape: 'pill', width: Math.min(400, Math.round(width)), locale: locale });
        }
        host.style.left = left + 'px';
        host.style.top = top + 'px';
    }""",
)

private fun hideButton(): Unit = js("{ document.getElementById('art-split-google-button')?.remove(); }")

private fun promptSilently(): Unit = js("{ google.accounts.id.prompt(); }")

private fun disableAutoSelect(): Unit = js("{ if (globalThis.google) google.accounts.id.disableAutoSelect(); }")

object WebGoogleAuth : GoogleAuth {
    private var pending: ((String) -> Unit)? = null

    suspend fun init() {
        gisInit(AppConfig.GOOGLE_WEB_CLIENT_ID).await<JsAny?>()
        setCredentialCallback { token -> pending?.invoke(token) }
    }

    /** FedCM / One Tap auto re-authentication for a returning user; null if Google wants interaction. */
    override suspend fun refresh(): String? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            pending = { token -> pending = null; if (continuation.isActive) continuation.resumeWith(Result.success(token)) }
            continuation.invokeOnCancellation { pending = null }
            promptSilently()
        }
    }

    override suspend fun signOut() = disableAutoSelect()

    internal fun onSignIn(handler: ((String) -> Unit)?) {
        pending = handler
    }
}

@Composable
actual fun GoogleSignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    val locale = language
    val callback by rememberUpdatedState(onIdToken)
    val errorCallback by rememberUpdatedState(onError)
    var ready by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(Unit) {
        try {
            WebGoogleAuth.init()
            ready = true
        } catch (e: Throwable) {
            errorCallback(e.message ?: "Google sign-in is unavailable")
        }
    }
    // A placeholder of the button's size; the DOM button is positioned over it.
    Box(modifier.fillMaxWidth().height(48.dp).onGloballyPositioned { bounds = it.boundsInWindow() })
    val rect = bounds
    if (ready && rect != null) {
        LaunchedEffect(rect) {
            WebGoogleAuth.onSignIn { callback(it) }
            showButton(rect.left / density.toDouble(), rect.top / density.toDouble(), rect.width / density.toDouble(), locale)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            WebGoogleAuth.onSignIn(null)
            hideButton()
        }
    }
}
