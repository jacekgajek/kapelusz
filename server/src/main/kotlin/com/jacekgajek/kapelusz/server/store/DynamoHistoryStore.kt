package com.jacekgajek.kapelusz.server.store

import com.jacekgajek.kapelusz.server.utf8Size
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

/**
 * [HistoryStore] in a DynamoDB table: partition key `band`, sort key `sk` (the record's key), the JSON in `entry`.
 * A page of history is one query, instead of a read per file.
 */
class DynamoHistoryStore(
    private val table: String,
    private val timer: CallTimer,
    private val dynamo: DynamoDbClient,
) : HistoryStore {

    override fun newestFirst(bandId: String, from: String, to: String): Sequence<HistoryRecord> = sequence {
        var start: Map<String, AttributeValue>? = null
        do {
            val request = QueryRequest.builder().tableName(table)
                .keyConditionExpression("#band = :band AND #sk BETWEEN :from AND :to")
                .expressionAttributeNames(mapOf("#band" to BAND, "#sk" to KEY))
                .expressionAttributeValues(mapOf(":band" to text(bandId), ":from" to text(from), ":to" to text(to)))
                .scanIndexForward(false)
                .apply { if (start != null) exclusiveStartKey(start) }
                .build()
            // A page of up to 1 MB; the next one only if the caller reads that far.
            val response = timer.timed { dynamo.query(request) }
            response.items().forEach { yield(HistoryRecord(it.getValue(KEY).s(), it.getValue(ENTRY).s())) }
            start = response.lastEvaluatedKey().takeIf { response.hasLastEvaluatedKey() && it.isNotEmpty() }
        } while (start != null)
    }

    override fun put(bandId: String, records: List<HistoryRecord>) {
        // A transaction takes up to 100 items and 4 MB: a usual push is one write, all or nothing.
        var chunk = mutableListOf<TransactWriteItem>()
        var bytes = 0
        fun write() {
            if (chunk.isEmpty()) return
            val items = chunk
            timer.timed { dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build()) }
            chunk = mutableListOf()
            bytes = 0
        }
        for (record in records) {
            val size = bandId.utf8Size() + record.key.utf8Size() + record.content.utf8Size() + ITEM_OVERHEAD
            if (chunk.size == MAX_TRANSACTION || bytes + size > MAX_TRANSACTION_BYTES) write()
            val item = mapOf(BAND to text(bandId), KEY to text(record.key), ENTRY to text(record.content))
            chunk += TransactWriteItem.builder().put(Put.builder().tableName(table).item(item).build()).build()
            bytes += size
        }
        write()
    }

    override fun deleteBand(bandId: String) {
        // A push appended while the band is being deleted may leave records behind; nothing reads them any more.
        keys(bandId).chunked(MAX_BATCH).forEach { chunk ->
            var requests = chunk.map { key ->
                WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(mapOf(BAND to text(bandId), KEY to text(key))).build()).build()
            }
            // DynamoDB may leave some for later when busy: those again, a few times, waiting longer each time.
            for (attempt in 0 until MAX_ATTEMPTS) {
                if (requests.isEmpty()) return@forEach
                if (attempt > 0) Thread.sleep(RETRY_DELAY_MS shl attempt)
                val response = timer.timed { dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(mapOf(table to requests)).build()) }
                requests = response.unprocessedItems()[table].orEmpty()
            }
            check(requests.isEmpty()) { "${requests.size} history records of band $bandId not deleted" }
        }
    }

    /** All keys of [bandId]'s records, without their content (a key condition may not have an empty bound). */
    fun keys(bandId: String): List<String> {
        val keys = mutableListOf<String>()
        var start: Map<String, AttributeValue>? = null
        do {
            val request = QueryRequest.builder().tableName(table)
                .keyConditionExpression("#band = :band")
                .projectionExpression("#sk")
                .expressionAttributeNames(mapOf("#band" to BAND, "#sk" to KEY))
                .expressionAttributeValues(mapOf(":band" to text(bandId)))
                .apply { if (start != null) exclusiveStartKey(start) }
                .build()
            val response = timer.timed { dynamo.query(request) }
            response.items().forEach { keys += it.getValue(KEY).s() }
            start = response.lastEvaluatedKey().takeIf { response.hasLastEvaluatedKey() && it.isNotEmpty() }
        } while (start != null)
        return keys
    }

    companion object {
        private const val BAND = "band"
        private const val KEY = "sk"
        private const val ENTRY = "entry"
        private const val MAX_TRANSACTION = 100

        /** Under the 4 MB a transaction may have, with room for what DynamoDB counts beyond the values. */
        private const val MAX_TRANSACTION_BYTES = 3_500_000

        /** Attribute names and the like, counted generously. */
        private const val ITEM_OVERHEAD = 100
        private const val RETRY_DELAY_MS = 50L
        private const val MAX_BATCH = 25
        private const val MAX_ATTEMPTS = 5

        private fun text(value: String): AttributeValue = AttributeValue.builder().s(value).build()

        fun defaultClient(region: Region): DynamoDbClient = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(region)
            .build()
    }
}
