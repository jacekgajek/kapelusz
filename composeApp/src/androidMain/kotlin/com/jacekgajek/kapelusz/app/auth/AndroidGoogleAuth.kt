package com.jacekgajek.kapelusz.app.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.sign_in_google
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Google sign-in through Android Credential Manager. Tokens are requested with the *web* client id as
 * `serverClientId`, so their audience is the one the server accepts.
 */
class AndroidGoogleAuth(private val app: Context) : GoogleAuth {
    /** Credential Manager shows its UI on top of an activity; MainActivity registers itself here. */
    var activity: WeakReference<Activity> = WeakReference(null)

    override suspend fun refresh(): String? {
        // Only on a screen the user sees (ITERATION_3 R7): Google's UI must not appear over a stopped or closing one.
        val activity = activity.get()?.takeIf { (it as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false } ?: return null
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(AppConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        return requestToken(activity, option)
    }

    override suspend fun signOut() {
        // Without an activity too (e.g. signed out from all devices during a background sync): otherwise the next
        // sign-in would auto-select the account again.
        val context = activity.get() ?: app
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }

    companion object {
        internal suspend fun signIn(activity: Activity): String? {
            val option = GetSignInWithGoogleOption.Builder(AppConfig.GOOGLE_WEB_CLIENT_ID).build()
            return requestToken(activity, option)
        }

        private suspend fun requestToken(activity: Activity, option: CredentialOption): String? = try {
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                null
            }
        } catch (ignored: GetCredentialCancellationException) {
            null
        }
    }
}

/** Credential Manager's "Sign in with Google", on the activity in front. */
@Composable
internal fun AndroidSignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    // Inside a dialog the context is a ContextThemeWrapper around the activity, not the activity itself.
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                try {
                    AndroidGoogleAuth.signIn(activity)?.let(onIdToken)
                } catch (e: GetCredentialException) {
                    onError(e.message ?: e.type)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { Text(stringResource(Res.string.sign_in_google)) }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity for $this")
}
