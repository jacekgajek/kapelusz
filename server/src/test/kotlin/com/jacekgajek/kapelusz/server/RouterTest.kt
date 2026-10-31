package com.jacekgajek.kapelusz.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouterTest {
    private val router = Router(
        listOf(
            Route("GET", "me") { HttpResponse(200, "me") },
            Route("GET", "bands/{bandId}/places/{placeId}") { HttpResponse(200, "details ${it.param("bandId")} ${it.param("placeId")}") },
            Route("POST", "bands/{bandId}/places/resolve") { HttpResponse(200, "resolve ${it.param("bandId")}") },
            Route("GET", "bands/{bandId}/places/resolve") { HttpResponse(200, "never: an earlier route matches") },
        ),
    )

    private fun answer(method: String, path: String): String? {
        val request = HttpRequest(method, path)
        return router.handle(request) { params -> Call(request, VerifiedUser("u1", null, null), params, null) }?.body
    }

    @Test
    fun parametersAreHandedOverByName() {
        assertEquals("details b1 p2", answer("GET", "/bands/b1/places/p2"))
    }

    @Test
    fun theMethodMustMatch() {
        assertEquals("resolve b1", answer("POST", "/bands/b1/places/resolve"))
        assertNull(answer("PUT", "/bands/b1/places/resolve"))
    }

    @Test
    fun theFirstMatchingRouteAnswers() {
        assertEquals("details b1 resolve", answer("GET", "/bands/b1/places/resolve"))
    }

    @Test
    fun slashesAroundAndBetweenSegmentsDoNotCount() {
        assertEquals("me", answer("GET", "/me/"))
        assertEquals("details b1 p2", answer("GET", "//bands//b1/places/p2"))
    }

    @Test
    fun noRouteForOtherPaths() {
        assertNull(answer("GET", "/me/picture"))
        assertNull(answer("GET", "/bands/b1/places"))
        assertNull(answer("GET", "/"))
    }
}
