package com.jacekgajek.kapelusz.server.store

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

/**
 * [GeocodeCache] in a DynamoDB table: partition key `key`; `lat` and `lng`, or `found` false with `expiresAt` (epoch
 * seconds, the table's TTL), which is checked here too as TTL deletes late.
 */
class DynamoGeocodeCache(
    private val table: String,
    private val timer: CallTimer,
    private val dynamo: DynamoDbClient,
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
