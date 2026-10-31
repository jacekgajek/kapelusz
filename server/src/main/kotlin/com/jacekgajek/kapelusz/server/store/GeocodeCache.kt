package com.jacekgajek.kapelusz.server.store

import java.security.MessageDigest

/** Where an address is. */
data class Located(val latitude: Double, val longitude: Double)

/**
 * Coordinates of addresses already looked up, by [key]: present with null when Google knows no such
 * place (for [NOT_FOUND_DAYS], then asked again), absent when never asked.
 */
interface GeocodeCache {
    fun get(keys: Collection<String>): Map<String, Located?>
    fun put(answers: Map<String, Located?>)

    companion object {
        /** An address's key: the SHA-256 of its normalised text, so the table holds no addresses as such. */
        fun key(normalised: String): String = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray()).joinToString("") { "%02x".format(it) }

        const val NOT_FOUND_DAYS = 30L
    }
}

class InMemoryGeocodeCache(private val clock: () -> Long = System::currentTimeMillis) : GeocodeCache {
    private val answers = mutableMapOf<String, Pair<Located?, Long>>()

    @Synchronized
    override fun get(keys: Collection<String>): Map<String, Located?> = keys.mapNotNull { key ->
        answers[key]?.takeIf { (at, savedAt) -> at != null || clock() - savedAt < GeocodeCache.NOT_FOUND_DAYS * DAY_MS }?.let { key to it.first }
    }.toMap()

    @Synchronized
    override fun put(answers: Map<String, Located?>) {
        answers.forEach { (key, at) -> this.answers[key] = at to clock() }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
