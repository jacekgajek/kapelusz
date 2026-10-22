package com.jacekgajek.kapelusz.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePolicyTest {
    private val now = 1_000_000L
    private fun may(memory: UpdateMemory = UpdateMemory(), autoUpdate: Boolean = true, visible: Boolean = false) =
        UpdatePolicy.mayInstallAutomatically(8, autoUpdate, visible, memory, now)

    @Test
    fun installsOnlyOutOfSightAndWithAutomaticUpdatesOn() {
        assertTrue(may())
        assertFalse(may(visible = true), "never restarts the app on screen")
        assertFalse(may(autoUpdate = false), "the switch stops installs, also of a version already downloaded")
    }

    @Test
    fun doesNotAskAgainAndAgain() {
        assertFalse(may(UpdateMemory(declinedVersion = 8)), "declined")
        assertFalse(may(UpdateMemory(failedVersion = 8)), "failed before")
        assertFalse(may(UpdateMemory(confirmVersion = 8)), "Android asked to confirm: waits for the user's tap")
        assertTrue(may(UpdateMemory(declinedVersion = 7, failedVersion = 7, confirmVersion = 7)), "a newer version is offered again")
    }

    @Test
    fun oneInstallAtATimeUnlessItGotLost() {
        val inFlight = UpdateMemory(installingVersion = 8, installingSince = now - 1000)
        assertFalse(may(inFlight))
        assertTrue(may(inFlight.copy(installingSince = now - UpdatePolicy.INSTALL_TIMEOUT_MS)), "no answer for 10 minutes: lost")
    }

    @Test
    fun waitingStatusKeepsWhatTheUserMustDo() {
        // These must survive a new check on app start, or the strip asking for them never shows.
        assertEquals(UpdateStatus.NeedsInstallPermission("1.0"), UpdatePolicy.waitingStatus(8, "1.0", canInstallApps = false, UpdateMemory()))
        assertEquals(UpdateStatus.NeedsConfirmation("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(confirmVersion = 8)))
        assertEquals(UpdateStatus.Declined("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(declinedVersion = 8)))
        assertEquals(UpdateStatus.InstallFailed("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(failedVersion = 8)))
        assertEquals(UpdateStatus.Ready("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(confirmVersion = 7)))
    }
}
