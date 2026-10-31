package com.jacekgajek.kapelusz.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private fun answer(method: String, path: String) = routes(HttpRequest(method, path)) {
        get("me") { HttpResponse(200, "me") }
        get("bands/{bandId}/places/{placeId}") { (bandId, placeId) -> HttpResponse(200, "details $bandId $placeId") }
        post("bands/{bandId}/places/resolve") { (bandId) -> HttpResponse(200, "resolve $bandId") }
        get("bands/{bandId}/places/resolve") { HttpResponse(200, "never: an earlier route matches") }
    }?.body

    @Test
    fun parametersAreHandedOverInOrder() {
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
