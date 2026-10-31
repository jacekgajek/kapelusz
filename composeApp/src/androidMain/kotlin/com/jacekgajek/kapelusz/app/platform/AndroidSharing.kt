package com.jacekgajek.kapelusz.app.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object AndroidSharing : Sharing {
    @Composable
    override fun rememberShare(): suspend (text: String) -> ShareResult {
        val context = LocalContext.current
        return remember(context) {
            { text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
                ShareResult.SHARED
            }
        }
    }
}
