package com.jacekgajek.kapelusz.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePolicyTest {
    private val now = 1_000_000L
    @Test
    fun waitingStatusKeepsWhatTheUserMustDo() {
        // These must survive a new check on app start, or the strip asking for them never shows.
        assertEquals(UpdateStatus.NeedsInstallPermission("1.0"), UpdatePolicy.waitingStatus(8, "1.0", canInstallApps = false, UpdateMemory()))
        assertEquals(UpdateStatus.NeedsConfirmation("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(confirmVersion = 8)))
        assertEquals(UpdateStatus.Declined("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(declinedVersion = 8)))
        assertEquals(UpdateStatus.InstallFailed("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(failedVersion = 8)))
        assertEquals(UpdateStatus.Ready("1.0"), UpdatePolicy.waitingStatus(8, "1.0", true, UpdateMemory(confirmVersion = 7)))
    }

    private fun afterCheck(memory: UpdateMemory = UpdateMemory(), installNow: Boolean = false, canInstall: Boolean = true) =
        UpdatePolicy.afterCheck(8, "1.0", installNow, canInstallApps = canInstall, memory = memory, now = now)

    /** V5-M1 C1′: updates download by themselves but install only when the user asks. */
    @Test
    fun aCheckInstallsOnlyWhenAskedAndOtherwiseSaysWhatWaits() {
        assertEquals(UpdateStep.Install, afterCheck(installNow = true))
        assertEquals(UpdateStep.Show(UpdateStatus.Ready("1.0")), afterCheck(), "downloaded: waits for the user's tap")
        assertEquals(UpdateStep.Show(UpdateStatus.Declined("1.0")), afterCheck(UpdateMemory(declinedVersion = 8)))
        assertEquals(UpdateStep.Show(UpdateStatus.NeedsInstallPermission("1.0")), afterCheck(canInstall = false))
    }

    @Test
    fun aNewCheckDoesNotInterruptAnInstallInFlight() {
        val inFlight = UpdateMemory().installing(8, now - 1000)
        assertEquals(UpdateStep.Show(UpdateStatus.Installing("1.0")), afterCheck(inFlight))
        assertEquals(UpdateStep.Show(UpdateStatus.Ready("1.0")), afterCheck(inFlight.copy(installingSince = now - UpdatePolicy.INSTALL_TIMEOUT_MS)), "lost: offered again")
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
