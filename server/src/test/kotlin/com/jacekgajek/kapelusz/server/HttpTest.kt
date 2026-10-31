package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What both transport edges share: the query string, and the answers for a bad one or a failure nobody caught. */
class HttpTest {
    private fun HttpResponse.error() = KapeluszJson.decodeFromString(ErrorResponse.serializer(), body!!)

    @Test
    fun theQueryStringIsDecodedAndOddPartsDropped() {
        assertEquals(emptyMap(), parseQuery(null))
        assertEquals(emptyMap(), parseQuery(""))
        assertEquals(mapOf("month" to "2026-09", "q" to "a b=c"), parseQuery("month=2026-09&flag&q=a+b%3Dc"))
        assertFailsWith<IllegalArgumentException> { parseQuery("month=%zz") }
    }

    @Test
    fun aBadQueryAndAnUncaughtFailureAreAnsweredAsJson() {
        val bad = malformedQuery()
        assertEquals(400, bad.status)
        assertEquals("application/json", bad.headers["content-type"])
        assertEquals(ErrorResponse(ApiErrors.BAD_REQUEST, "Malformed query string"), bad.error())
        val failed = unhandled("GET", "/me", IllegalStateException("boom"))
        assertEquals(500, failed.status)
        assertEquals(ErrorResponse(ApiErrors.INTERNAL, "Internal error"), failed.error())
    }
}
