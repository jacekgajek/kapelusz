package com.jacekgajek.kapelusz.server.devices

import com.jacekgajek.kapelusz.server.busy
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.StoredDevice
import com.jacekgajek.kapelusz.server.store.StoredDevices
import com.jacekgajek.kapelusz.server.store.devices

/** A device to send a sync message to, and whose it is (to forget an invalid token). */
data class DeviceTarget(val userId: String, val installationId: String, val token: String)

/** The device that made a change (the caller's own installation): no sync message for it about it. */
data class SenderDevice(val userId: String, val installationId: String)

/**
 * The users' devices for sync messages: registered by the app after sign-in and at each start,
 * removed on sign-out. Kept small: at most [MAX_DEVICES] per user, and none unseen for [MAX_UNSEEN_MS] (dropped on
 * every update, and when found while sending).
 */
internal class DeviceRegistry(private val store: BlobStore, private val clock: () -> Long) {

    fun register(userId: String, installationId: String, token: String) = update(userId) { devices ->
        val others = devices.filter { it.installationId != installationId }
        // The longest unseen gives way to a new device past the limit.
        (others.sortedByDescending { it.lastSeen }.take(MAX_DEVICES - 1) + StoredDevice(installationId, token, clock()))
    }

    fun remove(userId: String, installationId: String) = update(userId) { devices -> devices.filter { it.installationId != installationId } }

    /** After "Sign out on all devices", or the user's last band: no device of theirs gets messages any more. */
    fun clear(userId: String) = store.delete(StorageKeys.devices(userId))

    /** Firebase says the token is no longer valid (the app was uninstalled, or its data cleared). */
    fun forget(target: DeviceTarget) = update(target.userId) { devices -> devices.filter { it.token != target.token } }

    /** Drops devices unseen too long, when sending found some. */
    fun prune(userId: String) = update(userId) { it }

    /** All of the user's stored devices, the stale ones included ([fresh] tells them apart). */
    fun stored(userId: String): List<StoredDevice> = load(userId)?.first?.devices.orEmpty()

    fun fresh(device: StoredDevice): Boolean = clock() - device.lastSeen <= MAX_UNSEEN_MS

    private fun load(userId: String): Pair<StoredDevices, String>? = store.get(StorageKeys.devices(userId))?.let { decode(StoredDevices.serializer(), it.content) to it.etag }

    /**
     * Read, change, write if nothing changed meanwhile; devices unseen too long are dropped on the way. An emptied list
     * is written empty rather than deleted: a delete can't be conditional, and would remove a device registered meanwhile.
     */
    private fun update(userId: String, change: (List<StoredDevice>) -> List<StoredDevice>) {
        store.devices(userId).update(exhausted = { throw busy("Could not update the devices") }) { stored ->
            val before = stored?.devices.orEmpty()
            val after = change(before.filter(::fresh))
            if (after == before || (after.isEmpty() && stored == null)) stored else StoredDevices(after)
        }
    }

    companion object {
        const val MAX_DEVICES = 10
        const val MAX_UNSEEN_MS = 60L * 24 * 3600 * 1000
    }
}
