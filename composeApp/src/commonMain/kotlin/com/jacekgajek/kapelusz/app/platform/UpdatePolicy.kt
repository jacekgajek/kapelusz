package com.jacekgajek.kapelusz.app.platform

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
) {
    /** An install of [version] was handed to Android at [now]. */
    fun installing(version: Long, now: Long) = copy(installingVersion = version, installingSince = now)

    /** Android wants the user to confirm [version]. */
    fun confirming(version: Long) = copy(installingVersion = null, confirmVersion = version)

    /** The install of [version] ended without installing: the user [declined] it, or it failed. */
    fun finishedWithoutInstall(version: Long, declined: Boolean) =
        if (declined) copy(installingVersion = null, declinedVersion = version) else copy(installingVersion = null, failedVersion = version)

    /** The user asked for the update (Settings): earlier declines and failures no longer hold it back. */
    fun askedByUser() = copy(declinedVersion = null, failedVersion = null, installingVersion = null)
}

/** What to do with a downloaded build after a check: install it, or show where it stands. */
sealed interface UpdateStep {
    data object Install : UpdateStep
    data class Show(val status: UpdateStatus) : UpdateStep
}

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

    /**
     * The step after a check found the downloaded [version]: install it when the user asked ([installNow]) or the
     * rules allow; an install in flight is not interrupted; otherwise show what waits for the user.
     */
    fun afterCheck(
        version: Long,
        versionName: String,
        installNow: Boolean,
        autoUpdate: Boolean,
        appVisible: Boolean,
        canInstallApps: Boolean,
        memory: UpdateMemory,
        now: Long,
    ): UpdateStep = when {
        installNow || mayInstallAutomatically(version, autoUpdate, appVisible, memory, now) -> UpdateStep.Install
        isInstalling(version, memory, now) -> UpdateStep.Show(UpdateStatus.Installing(versionName))
        else -> UpdateStep.Show(waitingStatus(version, versionName, canInstallApps, memory))
    }

    /** The version of a downloaded build from its file name (`kapelusz-<versionCode>.apk`); null for any other file. */
    fun apkVersion(fileName: String): Long? =
        fileName.takeIf { it.startsWith(APK_PREFIX) && it.endsWith(".apk") }?.removePrefix(APK_PREFIX)?.removeSuffix(".apk")?.toLongOrNull()

    fun apkName(version: Long) = "$APK_PREFIX$version.apk"

    private const val APK_PREFIX = "kapelusz-"

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
