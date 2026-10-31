package com.jacekgajek.kapelusz.server.messaging

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.devices.DeviceTarget
import com.jacekgajek.kapelusz.server.devices.SenderDevice
import com.jacekgajek.kapelusz.server.store.devices
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tells devices that a band changed, so they sync it (I7 §2.2): only the band's id, no notification. Best
 * effort within about a second; the apps' fallback polling catches up with anything lost.
 */
interface SyncMessenger {
    /** The devices of [userIds] but [from] (the one that made the change). */
    fun bandChanged(bandId: String, userIds: Collection<String>, from: SenderDevice?)
}

/** Who a band's sync messages go to: its linked members who are still in it. */
internal fun Band.messagedUsers(): List<String> = members.filter { !it.archived }.mapNotNull { it.userId }

/** What happened to one message. */
enum class SendResult { SENT, INVALID_TOKEN, FAILED }

/** Sends one data message to each token; unanswered in time counts as [SendResult.FAILED]. Null: not set up. */
interface MessageSender {
    /** Whether messages can be sent at all (the key is in place); checked before any device is read. */
    fun ready(): Boolean

    fun send(tokens: List<String>, data: Map<String, String>): Map<String, SendResult>?
}

/** Where FCM isn't set up (tests, the local server): never ready, so no device is read and nothing is sent. */
object NoopMessageSender : MessageSender {
    override fun ready(): Boolean = false

    override fun send(tokens: List<String>, data: Map<String, String>): Map<String, SendResult>? = null
}

internal class DeviceMessenger(
    private val registry: DeviceRegistry,
    private val sender: MessageSender,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncMessenger {
    override fun bandChanged(bandId: String, userIds: Collection<String>, from: SenderDevice?) {
        // Not set up (no key yet): nothing to read or send.
        if (!runCatching { sender.ready() }.getOrDefault(false)) return
        // Each member's devices at once, within [READ_MS]; one that can't be read doesn't cost the others theirs.
        val deadline = clock() + READ_MS
        val reads = userIds.distinct().associateWith { userId -> CompletableFuture.supplyAsync({ registry.stored(userId) }, readers) }
        runCatching { CompletableFuture.allOf(*reads.values.toTypedArray()).get((deadline - clock()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        val stored = reads.mapNotNull { (userId, read) ->
            if (!read.isDone || read.isCompletedExceptionally) {
                null.also { System.err.println("Sync message for band $bandId: devices of a member not read") }
            } else {
                userId to read.join()
            }
        }
        val targets = stored.flatMap { (userId, devices) ->
            devices.filter { registry.fresh(it) && !(userId == from?.userId && it.installationId == from.installationId) }
                .map { DeviceTarget(userId, it.installationId, it.token) }
        }
        if (targets.isNotEmpty()) {
            val results = sender.send(targets.map { it.token }.distinct(), mapOf("band" to bandId)) ?: return
            // An uninstalled app (or one whose data was cleared): its token never works again.
            targets.filter { results[it.token] == SendResult.INVALID_TOKEN }.forEach { runCatching { registry.forget(it) } }
            val counts = results.values.groupingBy { it }.eachCount()
            if (counts.keys != setOf(SendResult.SENT)) println("SYNC band $bandId: $counts")
        }
        // Devices unseen for 60 days go when found, not only when their user registers again (I7 §2.4).
        stored.filter { (_, devices) -> devices.any { !registry.fresh(it) } }.forEach { (userId, _) -> runCatching { registry.prune(userId) } }
    }

    companion object {
        const val READ_MS = 400L

        /** Reading devices is waiting on S3: threads of their own, as many as a band's members, daemons. */
        private val readers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "devices").apply { isDaemon = true } }
    }
}
