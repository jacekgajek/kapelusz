package com.jacekgajek.artsplit.app.platform

/**
 * Link to the Android app to suggest on the website, or null when it should not be suggested: in the Android app
 * itself, on iPhone/iPad, after the user dismissed it, or when no APK is published.
 */
expect suspend fun androidAppDownloadUrl(): String?

/** Remembers that the user does not want the Android app suggested again. */
expect fun dismissAndroidAppSuggestion()
