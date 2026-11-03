package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

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

class RouterTest :
    ExpectSpec({

        expect("parameters are handed over by name") {
            answer("GET", "/bands/b1/places/p2") shouldBe "details b1 p2"
        }

        expect("the method must match") {
            answer("POST", "/bands/b1/places/resolve") shouldBe "resolve b1"
            answer("PUT", "/bands/b1/places/resolve").shouldBeNull()
        }

        expect("the first matching route answers") {
            answer("GET", "/bands/b1/places/resolve") shouldBe "details b1 resolve"
        }

        expect("slashes around and between segments do not count") {
            answer("GET", "/me/") shouldBe "me"
            answer("GET", "//bands//b1/places/p2") shouldBe "details b1 p2"
        }

        expect("no route for other paths") {
            answer("GET", "/me/picture").shouldBeNull()
            answer("GET", "/bands/b1/places").shouldBeNull()
            answer("GET", "/").shouldBeNull()
        }
    })
