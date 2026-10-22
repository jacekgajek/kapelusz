package com.jacekgajek.splitart.app.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The platform's "Sign in with Google" button; [onIdToken] receives the Google ID token. */
@Composable
expect fun GoogleSignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier)
