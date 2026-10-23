package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppVersionTest {
    private fun api(min: Int) = Api(InMemoryBlobStore(), InMemoryHistoryStore(), { VerifiedUser(it, null, null) }, minAppVersion = min)

    private fun me(api: Api, version: String?) = api.handle(
        HttpRequest("GET", "/me", mapOf("authorization" to "Bearer alice"), query = version?.let { mapOf(ApiErrors.APP_VERSION_PARAMETER to it) } ?: emptyMap()),
    )

    @Test
    fun everyAppIsServedWhileNoMinimumIsSet() {
        val api = api(0)
        assertEquals(200, me(api, null).status, "apps from before version 2 send no version")
        assertEquals(200, me(api, "3").status)
    }

    @Test
    fun olderAppsMustUpdate() {
        val api = api(12)
        val tooOld = me(api, "11")
        assertEquals(426, tooOld.status)
        assertTrue(tooOld.body!!.contains(ApiErrors.UPDATE_REQUIRED))
        assertEquals(426, me(api, null).status, "no version: from before version 2")
        assertEquals(200, me(api, "12").status)
        assertEquals(200, me(api, "0").status, "local development builds")
        assertEquals(204, api.handle(HttpRequest("OPTIONS", "/me")).status, "CORS preflights are not blocked")
    }
}
