@file:OptIn(ExperimentalWasmJsInterop::class)

package com.jacekgajek.kapelusz.app

import kotlin.js.JsAny
import kotlin.js.JsName
import kotlin.js.unsafeCast

/** Creates a plain JavaScript object for dictionary-shaped browser and third-party API arguments. */
@JsName("Object")
internal external class JsObject : JsAny {
    constructor()
}

internal fun <T : JsAny> jsObject(): T = JsObject().unsafeCast<T>()
