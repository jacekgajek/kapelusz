package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class RequestLineTest :
    ExpectSpec({

        expect("a line is read back as it was written, with the timers in between") {
            val line = RequestLine("POST", "/bands/b1/ops", 409, 37, "1076", "install-1", "250", "busy")

            val written = line.format("s3=3 calls/20ms db=0 calls/0ms")

            written shouldBe "TIMING POST /bands/b1/ops 409 37ms s3=3 calls/20ms db=0 calls/0ms " +
                "user=1076 install=install-1 app=250 error=busy"
            RequestLine.parse(written) shouldBe line
            RequestLine.parse("$written\n") shouldBe line
            RequestLine.parse(line.format()) shouldBe line
        }

        expect("what is not known is a dash, and is read back as nothing") {
            val line = RequestLine("GET", "/me", 401, 2)

            line.format() shouldBe "TIMING GET /me 401 2ms user=- install=- app=- error=-"
            RequestLine.parse(line.format()) shouldBe line
        }

        expect("a value that could break the line is left out") {
            val line = RequestLine("GET", "/me", 200, 1, "a b", "x user=root", "9".repeat(80), "bad\nline")

            RequestLine.parse(line.format()) shouldBe RequestLine("GET", "/me", 200, 1)
        }

        expect("a path or method with a space or a line break cannot write a line of its own") {
            val forged = "/x\nTIMING POST /bands/b/ops 500 1ms user=victim install=- app=250 error=internal\n/y"
            val line = RequestLine("GET", forged, 404, 3, appVersion = "250")

            val written = line.format("s3=0 calls/0ms")

            written.lines().size shouldBe 1
            RequestLine.parse(written)!!.let {
                it.status shouldBe 404
                it.userId.shouldBeNull()
                it.method shouldBe "GET"
            }
            RequestLine.parse(RequestLine("GET", "/me 500 1ms", 404, 3).format())!!.status shouldBe 404
            RequestLine.parse(RequestLine("GET /me 500", "/x", 404, 3).format())!!.method shouldBe "?"
            RequestLine.parse(RequestLine("GET", "", 404, 3).format())!!.path shouldBe "/"
        }

        expect("secrets in a path are taken out before it is logged") {
            RequestLine.withoutSecrets("/calendar/abcDEF123.ics") shouldBe "/calendar/…"
            RequestLine.withoutSecrets("/invites/ABC234/accept") shouldBe "/invites/…/accept"
            RequestLine.withoutSecrets("/join/ABC234") shouldBe "/join/…"
            RequestLine.withoutSecrets("/bands/b1/state") shouldBe "/bands/b1/state"
        }

        expect("other lines, and lines from before these fields, are not requests") {
            RequestLine.parse("TIMING GET /me 200 5ms s3=1 calls/3ms db=0 calls/0ms").shouldBeNull()
            RequestLine.parse("TIMING ? - 5ms s3=0 calls/0ms db=0 calls/0ms").shouldBeNull()
            RequestLine.parse("Unhandled error for GET /me: java.lang.IllegalStateException").shouldBeNull()
            RequestLine.parse("").shouldBeNull()
        }

        expect("the line of a request takes the device and version from the query, and the error from a refusal") {
            val query = mapOf(ApiErrors.INSTALLATION_PARAMETER to "install-1", ApiErrors.APP_VERSION_PARAMETER to "250")
            val request = HttpRequest("GET", "/bands/b1/state", query = query)

            val refused = RequestLine.of(request, "/bands/b1/state", error(404, ApiErrors.BAND_NOT_FOUND, "No such band"), 12, "1076")
            val fine = RequestLine.of(request, "/bands/b1/state", HttpResponse(200, """{"error":"not an error"}"""), 12, null)

            refused shouldBe RequestLine("GET", "/bands/b1/state", 404, 12, "1076", "install-1", "250", ApiErrors.BAND_NOT_FOUND)
            fine shouldBe RequestLine("GET", "/bands/b1/state", 200, 12, null, "install-1", "250", null)
        }

        expect("the API says who a request was from, and nobody when the sign-in is refused") {
            val harness = ApiHarness()
            val seen = mutableListOf<String>()

            harness.api.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer alice"))) { seen += it }.status shouldBe 200
            harness.api.handle(HttpRequest("GET", "/me")) { seen += it }.status shouldBe 401

            seen shouldBe listOf("alice")
        }
    })
