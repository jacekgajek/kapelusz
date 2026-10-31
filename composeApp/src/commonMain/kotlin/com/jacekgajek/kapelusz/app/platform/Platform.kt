package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.staticCompositionLocalOf
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.data.KeyValueStore

enum class PlatformKind { ANDROID, WEB }

/**
 * What the app needs from where it runs (REFACTORING.md 2.5): one object per platform (`AndroidPlatform`,
 * `WebPlatform`), given to `AppRuntime` and to screens through [LocalPlatform]; `FakePlatform` in tests. A capability
 * a platform lacks is null, so common code asks "is there one?" where it can; [kind] is for what is about the platform
 * itself (a browser can add a Google Calendar by URL, a phone cannot; the web's install hints against Play's updates).
 * The members a screen uses from a tap (maps, PDF, QR, clipboard, sharing, the picker, the sign-in button) still come
 * as `expect`/`actual` functions; they follow.
 */
interface Platform {
    val kind: PlatformKind

    /** The app's own persisted data (bands, session, settings). */
    val store: KeyValueStore

    /** Attachment files kept on the device by attachment id (ITERATION_5 §4.2). */
    val fileCache: ByteCache

    val google: GoogleAuth

    /** Firebase sync messages (ITERATION_7 §2.3); null on the web. */
    val messages: MessageChannel?

    /** Reloads the app from the server (the web's way to update); null where updating works differently (Android). */
    val reload: (() -> Unit)?

    /**
     * The Android app's page on Google Play, which updates it and is the only place it comes from (ITERATION_7 §1.1,
     * Play only since 2026-10-27); null on the web.
     */
    val storeListing: String?

    /** PUTs the file to a presigned [url] with exactly the signed [headers]; returns the HTTP status. */
    suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int

    /** The web's state for the install hints; null in the Android app. */
    suspend fun webInstallState(): WebInstallState?
}

/** The [Platform] for screens; provided once, at the root. */
val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided") }

/** Files by key; see [Platform.fileCache]. */
interface ByteCache {
    suspend fun read(key: String): ByteArray?

    suspend fun write(key: String, bytes: ByteArray)
}
