package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable

/** What happened when sharing: shown in a share sheet, or copied to the clipboard as a fallback. */
enum class ShareResult { SHARED, COPIED, FAILED }

/** Returns a function that shares [text] with the platform's share sheet (or copies it where there is none). */
@Composable
expect fun rememberShare(): suspend (text: String) -> ShareResult

/** Returns a function that copies [text] to the clipboard; true on success. */
@Composable
expect fun rememberCopy(): suspend (text: String) -> Boolean

/** Returns a function that reads the clipboard's text (null if empty or not allowed). */
@Composable
expect fun rememberPaste(): suspend () -> String?
