package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.auth.GoogleSignInButton
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.app_name
import com.jacekgajek.kapelusz.app.generated.sign_in_failed
import com.jacekgajek.kapelusz.app.generated.sign_in_intro
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.jacekgajek.kapelusz.app.ui.components.VersionLabel

@Composable
fun SignInScreen(onIdToken: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // Clear of the system bars (edge to edge on Android 15+).
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.sign_in_intro), style = MaterialTheme.typography.bodyMedium)
            GoogleSignInButton(
                onIdToken = { token ->
                    scope.launch { runCatching { onIdToken(token) }.onFailure { error = it.message } }
                },
                onError = { error = it },
            )
            error?.let { Text(stringResource(Res.string.sign_in_failed, it), color = MaterialTheme.colorScheme.error) }
        }
        VersionLabel(Modifier.align(Alignment.BottomEnd))
    }
}
