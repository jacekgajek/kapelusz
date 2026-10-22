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

    private fun afterCheck(memory: UpdateMemory = UpdateMemory(), installNow: Boolean = false, visible: Boolean = true, canInstall: Boolean = true) =
        UpdatePolicy.afterCheck(8, "1.0", installNow, autoUpdate = true, appVisible = visible, canInstallApps = canInstall, memory = memory, now = now)

    @Test
    fun aCheckInstallsWhenAskedOrAllowedAndOtherwiseSaysWhatWaits() {
        assertEquals(UpdateStep.Install, afterCheck(installNow = true), "the user asked: also on screen")
        assertEquals(UpdateStep.Install, afterCheck(visible = false), "out of sight with automatic updates on")
        assertEquals(UpdateStep.Show(UpdateStatus.Ready("1.0")), afterCheck(), "on screen: waits")
        assertEquals(UpdateStep.Show(UpdateStatus.Declined("1.0")), afterCheck(UpdateMemory(declinedVersion = 8), visible = false))
        assertEquals(UpdateStep.Show(UpdateStatus.NeedsInstallPermission("1.0")), afterCheck(canInstall = false))
    }

    @Test
    fun aNewCheckDoesNotInterruptAnInstallInFlight() {
        val inFlight = UpdateMemory().installing(8, now - 1000)
        assertEquals(UpdateStep.Show(UpdateStatus.Installing("1.0")), afterCheck(inFlight, visible = false))
        assertEquals(UpdateStep.Install, afterCheck(inFlight.copy(installingSince = now - UpdatePolicy.INSTALL_TIMEOUT_MS), visible = false), "lost")
    }

    @Test
    fun theMemoryFollowsTheInstall() {
        val installing = UpdateMemory().installing(8, now)
        assertEquals(UpdateMemory(installingVersion = 8, installingSince = now), installing)
        assertEquals(UpdateMemory(confirmVersion = 8, installingSince = now), installing.confirming(8))
        assertEquals(UpdateMemory(declinedVersion = 8, installingSince = now), installing.finishedWithoutInstall(8, declined = true))
        assertEquals(UpdateMemory(failedVersion = 8, installingSince = now), installing.finishedWithoutInstall(8, declined = false))
        val refused = UpdateMemory(declinedVersion = 8, failedVersion = 7, confirmVersion = 8, installingVersion = 8)
        assertEquals(UpdateMemory(confirmVersion = 8), refused.askedByUser(), "asked in Settings: earlier refusals no longer hold it back")
        assertTrue(may(UpdateMemory(declinedVersion = 8).askedByUser()))
    }

    @Test
    fun onlyAFinishedDownloadCounts() {
        assertEquals("kapelusz-42.apk", UpdatePolicy.apkName(42))
        assertEquals(42L, UpdatePolicy.apkVersion(UpdatePolicy.apkName(42)))
        assertEquals(null, UpdatePolicy.apkVersion("kapelusz-42.apk.part"), "still downloading")
        assertEquals(null, UpdatePolicy.apkVersion("kapelusz-x.apk"))
        assertEquals(null, UpdatePolicy.apkVersion("other.apk"))
    }
}
