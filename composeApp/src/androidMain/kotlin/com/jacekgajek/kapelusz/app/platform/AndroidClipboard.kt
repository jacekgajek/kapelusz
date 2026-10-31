package com.jacekgajek.kapelusz.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidClipboard : Clipboard {
    @Composable
    override fun rememberCopy(): suspend (text: String) -> Boolean {
        val context = LocalContext.current
        return remember(context) {
            { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Kapelusz", text))
                true
            }
        }
    }

    @Composable
    override fun rememberPaste(): suspend () -> String? {
        val context = LocalContext.current
        return remember(context) {
            {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }
        }
    }

    @Composable
    override fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean {
        val context = LocalContext.current
        return remember(context) {
            { bytes, fileName ->
                runCatching {
                    val extension = if (isPng(bytes)) "png" else "jpg"
                    val uri = withContext(Dispatchers.IO) { shareable(context, "clip", fileName, extension, bytes) }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    // The image itself (a content URI apps paste as a picture), not a link to it.
                    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, fileName, uri))
                    true
                }.getOrDefault(false)
            }
        }
    }
}

private fun isPng(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
