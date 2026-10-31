package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.inviteCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a scanned invite QR code gives the Join dialog. */
class InviteCodeTest {

    @Test
    fun aJoinLinksCode() {
        assertEquals("T2KTHM", inviteCode("https://kapelusz.jacekgajek.com/join/T2KTHM"))
        assertEquals("T2KTHM", inviteCode(" https://kapelusz.jacekgajek.com/join/t2kthm/ "))
        assertEquals("T2KTHM", inviteCode("https://kapelusz.jacekgajek.com/join/T2KTHM?utm=qr"))
    }

    @Test
    fun aBareCode() {
        assertEquals("ABC123", inviteCode("abc123"))
    }

    @Test
    fun anythingElseIsNoInvite() {
        assertNull(inviteCode("https://example.com/menu"))
        assertNull(inviteCode("https://kapelusz.jacekgajek.com/join/TOOLONG1"))
        assertNull(inviteCode("hello!"))
        assertNull(inviteCode("ĄBC123"), "only A–Z and 0–9")
    }
}
