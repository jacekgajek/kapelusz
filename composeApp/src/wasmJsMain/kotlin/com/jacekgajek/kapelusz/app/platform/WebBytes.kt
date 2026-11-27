@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app.platform

import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set

internal fun decodeBase64(base64: String): Uint8Array = window.atob(base64).let { binary ->
    Uint8Array(binary.length).also { bytes ->
        binary.indices.forEach { index -> bytes[index] = binary[index].code.toByte() }
    }
}
