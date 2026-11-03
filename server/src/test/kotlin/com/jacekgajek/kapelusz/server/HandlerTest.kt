package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.server.lambda.ApiGatewayRequest
import com.jacekgajek.kapelusz.server.lambda.ApiGatewayResponse
import com.jacekgajek.kapelusz.server.lambda.Handler
import com.jacekgajek.kapelusz.server.lambda.acceptsGzip
import com.jacekgajek.kapelusz.server.lambda.gatewayResponse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Base64
import java.util.zip.GZIPInputStream

private val bigJson = HttpResponse(
    200,
    """{"events":[${(1..200).joinToString(",") { "\"event $it\"" }}]}""",
    mapOf("content-type" to "application/json"),
)

class HandlerTest :
    ExpectSpec({

        expect("parses HTTP API payload v2") {
            val event = """
                {"version":"2.0","routeKey":"${'$'}default","rawPath":"/bands/b1/state","rawQueryString":"",
                 "headers":{"authorization":"Bearer t","if-none-match":"\"e1\""},
                 "requestContext":{"http":{"method":"GET","path":"/bands/b1/state"},"stage":"${'$'}default"},
                 "isBase64Encoded":false}
            """.trimIndent()
            val parsed = KapeluszJson.decodeFromString(ApiGatewayRequest.serializer(), event)
            parsed.requestContext.http.method shouldBe "GET"
            parsed.rawPath shouldBe "/bands/b1/state"
            parsed.headers["if-none-match"] shouldBe "\"e1\""
        }

        expect("a malformed query string is a bad request") {
            val event = """{"rawPath":"/bands/b1/log","rawQueryString":"month=%zz","requestContext":{"http":{"method":"GET"}}}"""
            val output = java.io.ByteArrayOutputStream()
            Handler().handleRequest(event.byteInputStream(), output, null)
            val response = KapeluszJson.decodeFromString(ApiGatewayResponse.serializer(), output.toString())
            response.statusCode shouldBe 400
            response.headers["content-type"] shouldBe "application/json"
            withClue(response.body) {
                response.body.orEmpty() shouldContain ApiErrors.BAD_REQUEST
                response.body.orEmpty() shouldContain "Malformed query string"
            }
        }

        expect("a large JSON body goes out gzipped to a client that takes it") {
            val out = gatewayResponse(bigJson, "gzip, deflate, br")
            out.isBase64Encoded shouldBe true
            out.headers["content-encoding"] shouldBe "gzip"
            out.headers["content-type"] shouldBe "application/json"
            val unpacked = GZIPInputStream(Base64.getDecoder().decode(out.body).inputStream()).readBytes().decodeToString()
            unpacked shouldBe bigJson.body
            out.body!!.length shouldBeLessThan bigJson.body!!.length / 2
        }

        expect("small, non-JSON or unwanted bodies stay as they are") {
            gatewayResponse(bigJson, null) shouldBe ApiGatewayResponse(200, bigJson.headers, bigJson.body)
            gatewayResponse(bigJson, "gzip;q=0, br") shouldBe ApiGatewayResponse(200, bigJson.headers, bigJson.body)
            val small = HttpResponse(200, """{"ok":true}""", mapOf("content-type" to "application/json"))
            gatewayResponse(small, "gzip") shouldBe ApiGatewayResponse(200, small.headers, small.body)
            val html = bigJson.copy(headers = mapOf("content-type" to "text/html; charset=utf-8"))
            gatewayResponse(html, "gzip") shouldBe ApiGatewayResponse(200, html.headers, html.body)
            val notModified = HttpResponse(304, null, mapOf("etag" to "\"e1\""))
            gatewayResponse(notModified, "gzip") shouldBe ApiGatewayResponse(304, notModified.headers, null)
        }

        expect("Accept-Encoding is read like browsers and OkHttp send it") {
            acceptsGzip("gzip") shouldBe true
            acceptsGzip("deflate, GZIP;q=0.5") shouldBe true
            acceptsGzip("*") shouldBe true
            acceptsGzip("br") shouldBe false
            acceptsGzip("gzip; q=0") shouldBe false
            acceptsGzip("gzip;q=0.000") shouldBe false
            acceptsGzip("") shouldBe false
            acceptsGzip("gzip;q=0, *") shouldBe false
        }

        expect("the Lambda leaves a small error body unpacked") {
            val event = """{"rawPath":"/nowhere","headers":{"accept-encoding":"gzip"},"requestContext":{"http":{"method":"GET"}}}"""
            val output = java.io.ByteArrayOutputStream()
            Handler().handleRequest(event.byteInputStream(), output, null)
            val response = KapeluszJson.decodeFromString(ApiGatewayResponse.serializer(), output.toString())
            response.isBase64Encoded shouldBe false
        }
    })
