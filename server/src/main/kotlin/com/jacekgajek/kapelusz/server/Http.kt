package com.jacekgajek.kapelusz.server

/** Transport-independent request; header names are lower-case. */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val query: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class HttpResponse(val status: Int, val body: String? = null, val headers: Map<String, String> = emptyMap())
