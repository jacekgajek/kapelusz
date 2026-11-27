@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app.platform

import com.jacekgajek.kapelusz.app.jsObject
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.unsafeCast
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

internal external interface TypedBlobOptions : JsAny {
    var type: String
}

internal fun decodeBase64(base64: String): Uint8Array = window.atob(base64).let { binary ->
    Uint8Array(binary.length).also { bytes ->
        binary.indices.forEach { index -> bytes[index] = binary[index].code.toByte() }
    }
}

internal fun blobParts(value: JsAny): JsArray<JsAny?> = JsArray<JsAny?>().apply { this[0] = value }

internal fun byteBlob(base64: String, mimeType: String): Blob = Blob(
    blobParts(decodeBase64(base64).unsafeCast<JsAny>()),
    jsObject<TypedBlobOptions>().apply { type = mimeType }.unsafeCast<BlobPropertyBag>(),
)
