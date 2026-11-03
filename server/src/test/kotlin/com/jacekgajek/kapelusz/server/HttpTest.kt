package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

private fun HttpResponse.error() = KapeluszJson.decodeFromString(ErrorResponse.serializer(), body!!)

/** What both transport edges share: the query string, and the answers for a bad one or a failure nobody caught. */
class HttpTest :
    ExpectSpec({

        expect("the query string is decoded and odd parts dropped") {
            parseQuery(null) shouldBe emptyMap()
            parseQuery("") shouldBe emptyMap()
            parseQuery("month=2026-09&flag&q=a+b%3Dc") shouldBe mapOf("month" to "2026-09", "q" to "a b=c")
            shouldThrow<IllegalArgumentException> { parseQuery("month=%zz") }
        }

        expect("a bad query and an uncaught failure are answered as JSON") {
            val bad = malformedQuery()
            bad.status shouldBe 400
            bad.headers["content-type"] shouldBe "application/json"
            bad.error() shouldBe ErrorResponse(ApiErrors.BAD_REQUEST, "Malformed query string")
            val failed = unhandled("GET", "/me", IllegalStateException("boom"))
            failed.status shouldBe 500
            failed.error() shouldBe ErrorResponse(ApiErrors.INTERNAL, "Internal error")
        }
    })
