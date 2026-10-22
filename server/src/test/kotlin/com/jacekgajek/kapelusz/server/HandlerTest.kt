package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
