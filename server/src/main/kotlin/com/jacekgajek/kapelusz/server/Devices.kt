package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.DeviceRegistration
import kotlinx.serialization.Serializable

/** An installation of the Android app that gets sync messages: its Firebase token, and when it last registered. */
@Serializable
data class StoredDevice(val installationId: String, val token: String, val lastSeen: Long)

/** `users/{userId}-devices.json`: the user's devices (ITERATION_7 §2.4). */
@Serializable
data class StoredDevices(val devices: List<StoredDevice> = emptyList())

/** A device to send a sync message to, and whose it is (to forget an invalid token). */
data class DeviceTarget(val userId: String, val installationId: String, val token: String)

/**
 * The users' devices for sync messages (ITERATION_7 §2.4): registered by the app after sign-in and at each start,
 * removed on sign-out. Kept small: at most [MAX_DEVICES] per user, and none unseen for [MAX_UNSEEN_MS].
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

    /** The devices of [userIds] to tell about a change, without [exceptInstallation] (the one that made it). */
    fun targets(userIds: Collection<String>, exceptInstallation: String?): List<DeviceTarget> = userIds.distinct().flatMap { userId ->
        val now = clock()
        load(userId)?.first?.devices.orEmpty()
            .filter { now - it.lastSeen <= MAX_UNSEEN_MS && it.installationId != exceptInstallation }
            .map { DeviceTarget(userId, it.installationId, it.token) }
    }

    private fun load(userId: String): Pair<StoredDevices, String>? =
        store.get(StorageKeys.devices(userId))?.let { decode(StoredDevices.serializer(), it.content) to it.etag }

    /** Read, change, write if nothing changed meanwhile; devices unseen too long are dropped on the way. */
    private fun update(userId: String, change: (List<StoredDevice>) -> List<StoredDevice>) {
        val key = StorageKeys.devices(userId)
        retryOnConflict(exhausted = { throw busy("Could not update the devices") }) {
            val loaded = load(userId)
            val now = clock()
            val before = loaded?.first?.devices.orEmpty()
            val after = change(before.filter { now - it.lastSeen <= MAX_UNSEEN_MS })
            when {
                after == before -> Unit
                after.isEmpty() && loaded != null -> store.delete(key)
                after.isEmpty() -> Unit
                else -> store.put(key, encode(StoredDevices.serializer(), StoredDevices(after)), ifMatch = loaded?.second, ifNoneMatch = loaded == null)
            }
        }
    }

    companion object {
        const val MAX_DEVICES = 10
        const val MAX_UNSEEN_MS = 60L * 24 * 3600 * 1000
    }
}

/** `PUT` and `DELETE /me/devices/{installationId}` (ITERATION_7 §2.4). */
internal class DeviceHandlers(private val registry: DeviceRegistry) {

    fun register(user: VerifiedUser, installationId: String, request: DeviceRegistration): HttpResponse {
        validInstallation(installationId)
        // Firebase tokens are long, but not that long, and have no spaces or quotes.
        badRequestIf(request.token.length !in 20..MAX_TOKEN_CHARS || !request.token.matches(Regex("[A-Za-z0-9_:.-]+"))) { "Invalid token" }
        registry.register(user.userId, installationId, request.token)
        return HttpResponse(204)
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
