package com.jacekgajek.kapelusz.app.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevSignInTest {
    @Test
    fun theTokenReadsAsAGoogleOneForTheSameNameEveryTime() {
        val now = 1_800_000_000_000
        val session = Session.fromIdToken(devIdToken("Zośka \"Z\" Nowak", now))
        assertEquals("dev-zo-ka-z-nowak", session.userId)
        assertEquals("Zośka \"Z\" Nowak", session.name)
        assertEquals("zo-ka-z-nowak@dev.local", session.email)
        assertTrue(session.expiresAt > now + 300L * 24 * 3600 * 1000, "never renewed through Google")
        assertEquals(session.userId, Session.fromIdToken(devIdToken("Zośka \"Z\" Nowak", now + 1)).userId)
        assertTrue(devIdToken("Alice", now).endsWith("."), "unsigned")
    }

    @Test
    fun aNameWithoutLettersOrDigitsSignsInAsNobody() {
        assertNull(devUserId(" !! "))
        assertEquals("dev-bob", devUserId("  Bob "))
    }
}
