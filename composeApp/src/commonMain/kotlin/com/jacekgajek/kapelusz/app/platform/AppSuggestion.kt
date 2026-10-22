package com.jacekgajek.kapelusz.app.platform

import kotlinx.serialization.Serializable

/** `/download/version.json` on the website, published together with the APK by the deploy. */
@Serializable
data class PublishedVersion(val versionCode: Long, val versionName: String)

/** The Android app to suggest: installing it (on the website) or updating to a newer published version (in the app). */
data class AppDownload(val url: String, val isUpdate: Boolean, val versionCode: Long, val versionName: String)

/**
 * What to suggest, or null when nothing should be: on iPhone/iPad, after the user dismissed it, when no APK is
 * published, or when the installed app is up to date.
 */
expect suspend fun appDownload(): AppDownload?

/** Stops suggesting [download]; an update is suggested again once an even newer version is published. */
expect fun dismissAppDownload(download: AppDownload)
