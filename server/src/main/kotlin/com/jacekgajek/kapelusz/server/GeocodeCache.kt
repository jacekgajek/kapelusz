package com.jacekgajek.kapelusz.server

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest
import java.security.MessageDigest

/** Where an address is. */
data class Located(val latitude: Double, val longitude: Double)

/**
 * Coordinates of addresses already looked up (ITERATION_7 §2f), by [key]: present with null when Google knows no such
 * place (for [NOT_FOUND_DAYS], then asked again), absent when never asked.
 */
interface GeocodeCache {
    fun get(keys: Collection<String>): Map<String, Located?>
    fun put(answers: Map<String, Located?>)

    companion object {
        /** An address's key: the SHA-256 of its normalised text, so the table holds no addresses as such. */
        fun key(normalised: String): String =
            MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray()).joinToString("") { "%02x".format(it) }

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

/**
 * [GeocodeCache] in a DynamoDB table: partition key `key`; `lat` and `lng`, or `found` false with `expiresAt` (epoch
 * seconds, the table's TTL), which is checked here too as TTL deletes late.
 */
class DynamoGeocodeCache(
    private val table: String,
    private val timer: CallTimer = CallTimer("db"),
    private val dynamo: DynamoDbClient = DynamoHistoryStore.defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GeocodeCache {

    override fun get(keys: Collection<String>): Map<String, Located?> {
        val result = mutableMapOf<String, Located?>()
        val now = clock() / 1000
        keys.distinct().chunked(MAX_GET).forEach { chunk ->
            var wanted: Map<String, KeysAndAttributes> = mapOf(table to KeysAndAttributes.builder().keys(chunk.map { mapOf(KEY to text(it)) }).build())
            var attempt = 0
            while (wanted.isNotEmpty() && attempt++ < MAX_ATTEMPTS) {
                val response = timer.timed { dynamo.batchGetItem(BatchGetItemRequest.builder().requestItems(wanted).build()) }
                response.responses()[table].orEmpty().forEach { item ->
                    val key = item.getValue(KEY).s()
                    if (item[FOUND]?.bool() == false) {
                        if ((item[EXPIRES]?.n()?.toLongOrNull() ?: 0) > now) result[key] = null
                    } else {
                        val lat = item[LAT]?.n()?.toDoubleOrNull()
                        val lng = item[LNG]?.n()?.toDoubleOrNull()
                        if (lat != null && lng != null) result[key] = Located(lat, lng)
                    }
                }
                // Throttled keys come back to be asked again.
                wanted = response.unprocessedKeys().orEmpty()
                if (wanted.isNotEmpty()) Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
        return result
    }

    override fun put(answers: Map<String, Located?>) {
        val now = clock() / 1000
        answers.entries.chunked(MAX_WRITE).forEach { chunk ->
            var writes: Map<String, List<WriteRequest>> = mapOf(
                table to chunk.map { (key, at) ->
                    val item = buildMap {
                        put(KEY, text(key))
                        if (at != null) {
                            put(LAT, number(at.latitude.toString()))
                            put(LNG, number(at.longitude.toString()))
                        } else {
                            put(FOUND, AttributeValue.builder().bool(false).build())
                            put(EXPIRES, number((now + GeocodeCache.NOT_FOUND_DAYS * 86_400).toString()))
                        }
                    }
                    WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build()
                },
            )
            var attempt = 0
            while (writes.isNotEmpty() && attempt++ < MAX_ATTEMPTS) {
                val response = timer.timed { dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(writes).build()) }
                writes = response.unprocessedItems().orEmpty()
                if (writes.isNotEmpty()) Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private companion object {
        const val KEY = "key"
        const val LAT = "lat"
        const val LNG = "lng"
        const val FOUND = "found"
        const val EXPIRES = "expiresAt"
        const val MAX_GET = 100
        const val MAX_WRITE = 25
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 50L

        fun text(value: String): AttributeValue = AttributeValue.builder().s(value).build()
        fun number(value: String): AttributeValue = AttributeValue.builder().n(value).build()
    }
}
