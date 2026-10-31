package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.model.KapeluszJson
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandlerTest {
    @Test
    fun parsesHttpApiPayloadV2() {
        val event = """
            {"version":"2.0","routeKey":"${'$'}default","rawPath":"/bands/b1/state","rawQueryString":"",
             "headers":{"authorization":"Bearer t","if-none-match":"\"e1\""},
             "requestContext":{"http":{"method":"GET","path":"/bands/b1/state"},"stage":"${'$'}default"},
             "isBase64Encoded":false}
        """.trimIndent()
        val parsed = KapeluszJson.decodeFromString(ApiGatewayRequest.serializer(), event)
        assertEquals("GET", parsed.requestContext.http.method)
        assertEquals("/bands/b1/state", parsed.rawPath)
        assertEquals("\"e1\"", parsed.headers["if-none-match"])
    }

    @Test
    fun malformedQueryStringIsABadRequest() {
        val event = """{"rawPath":"/bands/b1/log","rawQueryString":"month=%zz","requestContext":{"http":{"method":"GET"}}}"""
        val output = java.io.ByteArrayOutputStream()
        Handler().handleRequest(event.byteInputStream(), output, null)
        assertEquals(400, KapeluszJson.decodeFromString(ApiGatewayResponse.serializer(), output.toString()).statusCode)
    }

    private val bigJson = HttpResponse(200, """{"events":[${(1..200).joinToString(",") { "\"event $it\"" }}]}""", mapOf("content-type" to "application/json"))

    @Test
    fun aLargeJsonBodyGoesOutGzippedToAClientThatTakesIt() {
        val out = gatewayResponse(bigJson, "gzip, deflate, br")
        assertTrue(out.isBase64Encoded)
        assertEquals("gzip", out.headers["content-encoding"])
        assertEquals("application/json", out.headers["content-type"])
        val unpacked = GZIPInputStream(Base64.getDecoder().decode(out.body).inputStream()).readBytes().decodeToString()
        assertEquals(bigJson.body, unpacked)
        assertTrue(out.body!!.length < bigJson.body!!.length / 2)
    }

    @Test
    fun smallNonJsonOrUnwantedBodiesStayAsTheyAre() {
        assertEquals(ApiGatewayResponse(200, bigJson.headers, bigJson.body), gatewayResponse(bigJson, null))
        assertEquals(ApiGatewayResponse(200, bigJson.headers, bigJson.body), gatewayResponse(bigJson, "gzip;q=0, br"))
        val small = HttpResponse(200, """{"ok":true}""", mapOf("content-type" to "application/json"))
        assertEquals(ApiGatewayResponse(200, small.headers, small.body), gatewayResponse(small, "gzip"))
        val html = bigJson.copy(headers = mapOf("content-type" to "text/html; charset=utf-8"))
        assertEquals(ApiGatewayResponse(200, html.headers, html.body), gatewayResponse(html, "gzip"))
        val notModified = HttpResponse(304, null, mapOf("etag" to "\"e1\""))
        assertEquals(ApiGatewayResponse(304, notModified.headers, null), gatewayResponse(notModified, "gzip"))
    }

    @Test
    fun acceptEncodingIsReadLikeBrowsersAndOkHttpSendIt() {
        assertTrue(acceptsGzip("gzip"))
        assertTrue(acceptsGzip("deflate, GZIP;q=0.5"))
        assertTrue(acceptsGzip("*"))
        assertFalse(acceptsGzip("br"))
        assertFalse(acceptsGzip("gzip; q=0"))
        assertFalse(acceptsGzip("gzip;q=0.000"))
        assertFalse(acceptsGzip(""))
        assertFalse(acceptsGzip("gzip;q=0, *"))
    }

    @Test
    fun theLambdaLeavesASmallErrorBodyUnpacked() {
        val event = """{"rawPath":"/nowhere","headers":{"accept-encoding":"gzip"},"requestContext":{"http":{"method":"GET"}}}"""
        val output = java.io.ByteArrayOutputStream()
        Handler().handleRequest(event.byteInputStream(), output, null)
        val response = KapeluszJson.decodeFromString(ApiGatewayResponse.serializer(), output.toString())
        assertFalse(response.isBase64Encoded)
    }
}
