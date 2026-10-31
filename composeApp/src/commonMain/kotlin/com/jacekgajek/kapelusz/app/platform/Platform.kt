package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.auth.GoogleAuth
import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.text.PlayedPlaces

enum class PlatformKind { ANDROID, WEB }

/**
 * What the app needs from where it runs (REFACTORING.md 2.5): one object per platform (`AndroidPlatform`,
 * `WebPlatform`), given to `AppRuntime` and to screens through [LocalPlatform]; `FakePlatform` in tests. A capability
 * a platform lacks ([maps], [pdf], [qrScanner], [messages], [reload], [storeListing]) is null, so common code asks
 * "is there one?" where it can; [kind] is for what is about the platform itself (a browser can add a Google Calendar by
 * URL, a phone cannot; the web's install hints against Play's updates). What a screen uses from the composition (the
 * pickers, the sign-in button, Play's updates, the locale) is a composable member.
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

    val clipboard: Clipboard

    val sharing: Sharing

    /** Maps to pick a place on and of where the band played (ITERATION_6 §3, ITERATION_7 §8); null on the web, and without a Maps key. */
    val maps: Maps?

    /** PDFs shown in the app (Android renders them); null where the browser shows them. */
    val pdf: PdfRenderer?

    /** A camera QR scanner (ITERATION_6 §10); null on the web, where a phone's own camera opens the invite link anyway. */
    val qrScanner: QrScanner?

    /** PUTs the file to a presigned [url] with exactly the signed [headers]; returns the HTTP status. */
    suspend fun uploadFile(url: String, headers: Map<String, String>, base64: String): Int

    /** The web's state for the install hints; null in the Android app. */
    suspend fun webInstallState(): WebInstallState?

    /** Google Play's in-app updates (ITERATION_7 §4); null on the web, and where Play can't be asked. */
    @Composable
    fun rememberStoreUpdates(): StoreUpdates?

    /** The platform's pickers; results (not cancellations) arrive in [onResult], one per chosen file. */
    @Composable
    fun rememberAttachmentPicker(onResult: (List<PickResult>) -> Unit): AttachmentPicker

    /**
     * A function that opens the URL [resolve] produces (a presigned download) in the browser or system viewer. On the
     * web the window is opened right away, while the click still counts as a user action, so it is not blocked as a
     * popup; [resolve] returning null closes it again.
     */
    @Composable
    fun rememberUrlOpener(): (resolve: suspend () -> String?) -> Unit

    /** The platform's "Sign in with Google" button; [onIdToken] receives the Google ID token. */
    @Composable
    fun SignInButton(onIdToken: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier)

    /**
     * The locale Compose resources use, [language] from the in-app setting (null: the device's), as a value for
     * `CompositionLocalProvider`: the Compose Multiplatform "change locale in app" recipe, per platform.
     */
    @Composable
    fun provideLocale(language: String?): ProvidedValue<*>
}

/** The [Platform] for screens; provided once, at the root. */
val LocalPlatform = staticCompositionLocalOf<Platform> { error("No Platform provided") }

/** Files by key; see [Platform.fileCache]. */
interface ByteCache {
    suspend fun read(key: String): ByteArray?

    suspend fun write(key: String, bytes: ByteArray)
}

/** The clipboard, as functions a screen remembers (they may need the composition's context). */
interface Clipboard {
    /** Copies the text; true on success. */
    @Composable
    fun rememberCopy(): suspend (text: String) -> Boolean

    /** Reads the clipboard's text (null if empty or not allowed). */
    @Composable
    fun rememberPaste(): suspend () -> String?

    /** Puts the image itself on the clipboard (a JPEG or PNG file's bytes); false where the platform cannot. */
    @Composable
    fun rememberCopyImage(): suspend (bytes: ByteArray, fileName: String) -> Boolean
}

interface Sharing {
    /** Shares the text with the platform's share sheet (or copies it where there is none). */
    @Composable
    fun rememberShare(): suspend (text: String) -> ShareResult
}

/** Google Maps (ITERATION_6 §3, ITERATION_7 §8). */
interface Maps {
    /**
     * A full-screen map to pick a place on, e.g. in the middle of a forest: a tap puts the pin (a place on the map: its
     * pin and name). Opens at [start] if given, else over Poland. [onPick] gets the pin and the place's name.
     */
    @Composable
    fun PickerDialog(
        start: Pair<Double, Double>?,
        onPick: (latitude: Double, longitude: Double, name: String?) -> Unit,
        onDismiss: () -> Unit,
    )

    /**
     * A map of [places], one marker each; a marker's bubble opens [onPlace]. [bottomInset]: covered by a panel, kept
     * clear of controls and markers.
     */
    @Composable
    fun PlacesMap(
        places: List<PlayedPlaces.Place>,
        onPlace: (PlayedPlaces.Place) -> Unit,
        modifier: Modifier = Modifier,
        bottomInset: Dp = 0.dp,
    )
}

/** PDFs rendered by the app (ITERATION_5 §4.3). */
interface PdfRenderer {
    /** The pages of a PDF, one under another, each zoomable; [onPage] gets the page mostly in view. */
    @Composable
    fun Pages(bytes: ByteArray, modifier: Modifier, onPage: (Int) -> Unit)

    /** One page of a PDF as a PNG, e.g. to copy it; null when it could not be rendered. */
    @Composable
    fun rememberPagePng(): suspend (bytes: ByteArray, page: Int) -> ByteArray?

    /** Opens a PDF in the device's reader app; false when there is none. */
    @Composable
    fun rememberOpen(): suspend (bytes: ByteArray, fileName: String) -> Boolean
}

/** Scans a QR code with the camera (ITERATION_6 §10), e.g. an invite's. */
interface QrScanner {
    @Composable
    fun rememberScan(): suspend () -> QrScan
}
