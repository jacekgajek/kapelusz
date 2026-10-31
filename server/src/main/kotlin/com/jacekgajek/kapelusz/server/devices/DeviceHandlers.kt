package com.jacekgajek.kapelusz.server.devices

import com.jacekgajek.kapelusz.api.DeviceRegistration
import com.jacekgajek.kapelusz.api.DeviceRegistrationResponse
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.badRequestIf
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.store.devices
import com.jacekgajek.kapelusz.server.users.UserRecords

/** `PUT` and `DELETE /me/devices/{installationId}` (I7 §2.4). */
internal class DeviceHandlers(
    private val registry: DeviceRegistry,
    private val users: UserRecords,
    /** Whether messages can be sent at all (the FCM key is in place). */
    private val messaging: () -> Boolean,
) {

    /**
     * Stores the device only while the user is in a band: after their last one, the server keeps nothing of theirs
     * (I6 §11a), and an app starting again doesn't bring it back. The answer says whether messages will come,
     * so the app knows how often to look for news itself.
     */
    fun register(call: Call): HttpResponse {
        val user = call.user
        val installationId = call.param("installationId")
        val request = call.body(DeviceRegistration.serializer())
        validInstallation(installationId)
        // Firebase tokens are long, but not that long, and have no spaces or quotes.
        badRequestIf(request.token.length !in 20..MAX_TOKEN_CHARS || !request.token.matches(Regex("[A-Za-z0-9_:.-]+"))) { "Invalid token" }
        val inBand = users.load(user.userId).bandIds.isNotEmpty()
        if (inBand) registry.register(user.userId, installationId, request.token)
        return ok(DeviceRegistrationResponse.serializer(), DeviceRegistrationResponse(messaging = inBand && runCatching { messaging() }.getOrDefault(false)))
    }

    fun remove(call: Call): HttpResponse {
        val user = call.user
        val installationId = call.param("installationId")
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
