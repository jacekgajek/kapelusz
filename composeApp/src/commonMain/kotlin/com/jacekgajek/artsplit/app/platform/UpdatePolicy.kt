package com.jacekgajek.artsplit.app.platform

/** What the updater remembers about a downloaded build between runs (Android keeps it in preferences). */
data class UpdateMemory(
    /** The user declined installing this version. */
    val declinedVersion: Long? = null,
    /** Installing this version failed (other than declining). */
    val failedVersion: Long? = null,
    /** Android asked the user to confirm this version. */
    val confirmVersion: Long? = null,
    /** An install of this version was handed to Android at [installingSince] and has not reported back yet. */
    val installingVersion: Long? = null,
    val installingSince: Long = 0,
)

/** The rules of automatic updates (ITERATION_2 §1), kept free of Android so they can be tested. */
object UpdatePolicy {
    /** An install handed to Android that has not reported back after this long is considered lost. */
    const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Whether a downloaded [version] may be installed now without the user asking for it: automatic updates are on,
     * the app is out of sight, the user has not declined this version, it did not fail before, Android has not asked
     * to confirm it (that waits for the user's tap instead of prompting on every exit), and no install of it is in
     * flight.
     */
    fun mayInstallAutomatically(version: Long, autoUpdate: Boolean, appVisible: Boolean, memory: UpdateMemory, now: Long): Boolean =
        autoUpdate && !appVisible && memory.declinedVersion != version && memory.failedVersion != version &&
            memory.confirmVersion != version && !isInstalling(version, memory, now)

    fun isInstalling(version: Long, memory: UpdateMemory, now: Long): Boolean =
        memory.installingVersion == version && now - memory.installingSince < INSTALL_TIMEOUT_MS

    /** What to show for a downloaded [version] that is not being installed right now. */
    fun waitingStatus(version: Long, versionName: String, canInstallApps: Boolean, memory: UpdateMemory): UpdateStatus = when {
        !canInstallApps -> UpdateStatus.NeedsInstallPermission(versionName)
        memory.declinedVersion == version -> UpdateStatus.Declined(versionName)
        memory.failedVersion == version -> UpdateStatus.InstallFailed(versionName)
        // Android wanted a confirmation last time: offer it in the app instead of trying silently on every exit.
        memory.confirmVersion == version -> UpdateStatus.NeedsConfirmation(versionName)
        else -> UpdateStatus.Ready(versionName)
    }
}
