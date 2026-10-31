package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.loading
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A progress indicator while the app loads, instead of a blank screen. It appears after a moment, so
 * a quick start does not flash it.
 */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        shown = true
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (shown) {
            val description = stringResource(Res.string.loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = description })
        }
    }
}
