package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun appVersionApi(min: Int) = ApiHarness(minAppVersion = min).api

private fun me(api: Api, version: String?) = api.handle(
    HttpRequest(
        "GET",
        "/me",
        mapOf("authorization" to "Bearer alice"),
        query = version?.let { mapOf(ApiErrors.APP_VERSION_PARAMETER to it) } ?: emptyMap(),
    ),
)

class AppVersionTest :
    ExpectSpec({

        expect("every app is served while no minimum is set") {
            val api = appVersionApi(0)
            withClue("apps from before version 2 send no version") { me(api, null).status shouldBe 200 }
            me(api, "3").status shouldBe 200
        }

        expect("older apps must update") {
            val api = appVersionApi(12)
            val tooOld = me(api, "11")
            tooOld.status shouldBe 426
            tooOld.body!! shouldContain ApiErrors.UPDATE_REQUIRED
            withClue("no version: from before version 2") { me(api, null).status shouldBe 426 }
            me(api, "12").status shouldBe 200
            withClue("local development builds") { me(api, "0").status shouldBe 200 }
            withClue("CORS preflights are not blocked") { api.handle(HttpRequest("OPTIONS", "/me")).status shouldBe 204 }
        }
    })
