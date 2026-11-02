package com.jacekgajek.kapelusz.app.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    @Composable
    override fun rememberOpen(mimeType: String): suspend (bytes: ByteArray, fileName: String) -> Boolean {
        val context = LocalContext.current
        return remember(context) {
            { bytes, fileName ->
                runCatching {
                    val uri = withContext(Dispatchers.IO) { shareable(context, "open", fileName, "pdf", bytes) }
                    val view = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(view)
                        true
                    } catch (ignored: ActivityNotFoundException) {
                        false
                    }
                }.getOrDefault(false)
            }
        }
    }
}
