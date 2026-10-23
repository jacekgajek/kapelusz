package com.jacekgajek.kapelusz.server

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

/** One operation of a push in a band's history: [content] is a one-operation `HistoryEntry` in JSON. */
data class HistoryRecord(val key: String, val content: String)

/**
 * A band's history, one record per operation (V5 history speed): [HistoryRecord.key] is the push's entry name and the
 * operation's place in it, `yyyy-MM/<time>-<uuid>.json#007`, so a band's records sort by time and a push's stay
 * together, in order.
 */
interface HistoryStore {
    /** Records of [bandId] with keys from [from] to [to] (both inclusive), newest first, read as they are used. */
    fun newestFirst(bandId: String, from: String, to: String): Sequence<HistoryRecord>

    /** Writes [records], all of one push. */
    fun put(bandId: String, records: List<HistoryRecord>)

    fun deleteBand(bandId: String)
}

/**
 * [HistoryStore] in a DynamoDB table: partition key `band`, sort key `sk` (the record's key), the JSON in `entry`.
 * A page of history is one query, instead of a read per file.
 */
class DynamoHistoryStore(
    private val table: String,
    private val timer: CallTimer = CallTimer("db"),
    private val dynamo: DynamoDbClient = defaultClient(),
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
        // A transaction takes up to 100 items: a usual push is one write, all or nothing.
        records.chunked(MAX_TRANSACTION).forEach { chunk ->
            val items = chunk.map { record ->
                val item = mapOf(BAND to text(bandId), KEY to text(record.key), ENTRY to text(record.content))
                TransactWriteItem.builder().put(Put.builder().tableName(table).item(item).build()).build()
            }
            timer.timed { dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build()) }
        }
    }

    override fun deleteBand(bandId: String) {
        val keys = newestFirst(bandId, "", "￿").map { it.key }.toList()
        keys.chunked(MAX_BATCH).forEach { chunk ->
            var requests = chunk.map { key ->
                WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(mapOf(BAND to text(bandId), KEY to text(key))).build()).build()
            }
            // DynamoDB may leave some for later when busy: those again, a few times.
            repeat(MAX_ATTEMPTS) {
                if (requests.isEmpty()) return@forEach
                val response = timer.timed { dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(mapOf(table to requests)).build()) }
                requests = response.unprocessedItems()[table].orEmpty()
            }
            check(requests.isEmpty()) { "${requests.size} history records of band $bandId not deleted" }
        }
    }

    companion object {
        private const val BAND = "band"
        private const val KEY = "sk"
        private const val ENTRY = "entry"
        private const val MAX_TRANSACTION = 100
        private const val MAX_BATCH = 25
        private const val MAX_ATTEMPTS = 5

        private fun text(value: String): AttributeValue = AttributeValue.builder().s(value).build()

        fun defaultClient(): DynamoDbClient = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(Region.of(System.getenv("AWS_REGION") ?: "eu-central-1"))
            .build()
    }
}
