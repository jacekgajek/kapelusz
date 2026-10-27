package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.DeviceRegistration
import com.jacekgajek.kapelusz.api.DeviceRegistrationResponse
import kotlinx.serialization.Serializable

/** An installation of the Android app that gets sync messages: its Firebase token, and when it last registered. */
@Serializable
data class StoredDevice(val installationId: String, val token: String, val lastSeen: Long)

/** `users/{userId}-devices.json`: the user's devices (ITERATION_7 §2.4). */
@Serializable
data class StoredDevices(val devices: List<StoredDevice> = emptyList())

/** A device to send a sync message to, and whose it is (to forget an invalid token). */
data class DeviceTarget(val userId: String, val installationId: String, val token: String)

/** The device that made a change (the caller's own installation): no sync message for it about it. */
data class SenderDevice(val userId: String, val installationId: String)

/**
 * The users' devices for sync messages (ITERATION_7 §2.4): registered by the app after sign-in and at each start,
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

    private fun load(userId: String): Pair<StoredDevices, String>? =
        store.get(StorageKeys.devices(userId))?.let { decode(StoredDevices.serializer(), it.content) to it.etag }

    /**
     * Read, change, write if nothing changed meanwhile; devices unseen too long are dropped on the way. An emptied list
     * is written empty rather than deleted: a delete can't be conditional, and would remove a device registered meanwhile.
     */
    private fun update(userId: String, change: (List<StoredDevice>) -> List<StoredDevice>) {
        val key = StorageKeys.devices(userId)
        retryOnConflict(exhausted = { throw busy("Could not update the devices") }) {
            val loaded = load(userId)
            val before = loaded?.first?.devices.orEmpty()
            val after = change(before.filter(::fresh))
            if (after == before || (after.isEmpty() && loaded == null)) return@retryOnConflict
            store.put(key, encode(StoredDevices.serializer(), StoredDevices(after)), ifMatch = loaded?.second, ifNoneMatch = loaded == null)
        }
    }

    companion object {
        const val MAX_DEVICES = 10
        const val MAX_UNSEEN_MS = 60L * 24 * 3600 * 1000
    }
}

/** `PUT` and `DELETE /me/devices/{installationId}` (ITERATION_7 §2.4). */
internal class DeviceHandlers(
    private val registry: DeviceRegistry,
    private val users: UserHandlers,
    /** Whether messages can be sent at all (the FCM key is in place). */
    private val messaging: () -> Boolean,
) {

    /**
     * Stores the device only while the user is in a band: after their last one, the server keeps nothing of theirs
     * (ITERATION_6 §11a), and an app starting again doesn't bring it back. The answer says whether messages will come,
     * so the app knows how often to look for news itself.
     */
    fun register(user: VerifiedUser, installationId: String, request: DeviceRegistration): HttpResponse {
        validInstallation(installationId)
        // Firebase tokens are long, but not that long, and have no spaces or quotes.
        badRequestIf(request.token.length !in 20..MAX_TOKEN_CHARS || !request.token.matches(Regex("[A-Za-z0-9_:.-]+"))) { "Invalid token" }
        val inBand = users.loadUser(user.userId).bandIds.isNotEmpty()
        if (inBand) registry.register(user.userId, installationId, request.token)
        return ok(DeviceRegistrationResponse.serializer(), DeviceRegistrationResponse(messaging = inBand && runCatching { messaging() }.getOrDefault(false)))
    }

    fun remove(user: VerifiedUser, installationId: String): HttpResponse {
        validInstallation(installationId)
        registry.remove(user.userId, installationId)
        return HttpResponse(204)
    }

    private fun validInstallation(id: String) = badRequestIf(!id.matches(INSTALLATION_ID)) { "Invalid installation id" }

    companion object {
        val INSTALLATION_ID = Regex("[A-Za-z0-9_-]{8,64}")
        const val MAX_TOKEN_CHARS = 4096
    }
}
