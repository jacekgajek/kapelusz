package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.server.admin.CloudWatchRequestLog
import com.jacekgajek.kapelusz.server.admin.InMemoryRequestLog
import com.jacekgajek.kapelusz.server.admin.LoggedRequest
import com.jacekgajek.kapelusz.server.admin.RequestLogUnavailable
import com.jacekgajek.kapelusz.server.admin.SyncFailures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryStatus
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.StopQueryRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.StopQueryResponse

/** A failed request of the app (version 250) by [user] from [installation]. */
private fun failed(
    method: String,
    path: String,
    status: Int,
    error: String? = null,
    user: String? = "bob",
    installation: String? = "phone-1",
    app: String? = "250",
) = RequestLine(method, path, status, 5, user, installation, app, error)

/** CloudWatch Logs with one query, whose answers come in the order of [answers]; the last one is repeated. */
private class FakeLogs(
    private val answers: List<GetQueryResultsResponse>,
    /** Thrown when a query is started, or when its results are asked for. */
    private val startFails: SdkClientException? = null,
    private val resultsFail: SdkClientException? = null,
) : CloudWatchLogsClient {
    val started = mutableListOf<StartQueryRequest>()
    val stopped = mutableListOf<String>()
    private var asked = 0

    override fun serviceName() = "logs"

    override fun close() = Unit

    override fun startQuery(request: StartQueryRequest): StartQueryResponse {
        startFails?.let { throw it }
        started += request
        return StartQueryResponse.builder().queryId("q1").build()
    }

    override fun getQueryResults(request: GetQueryResultsRequest): GetQueryResultsResponse {
        resultsFail?.let { throw it }
        return answers[minOf(asked++, answers.lastIndex)]
    }

    override fun stopQuery(request: StopQueryRequest): StopQueryResponse {
        stopped += request.queryId()
        return StopQueryResponse.builder().success(true).build()
    }
}

private fun field(name: String, value: String) = ResultField.builder().field(name).value(value).build()

private fun results(status: QueryStatus, vararg rows: Pair<String, String>) = GetQueryResultsResponse.builder().status(status)
    .results(rows.map { (at, message) -> listOf(field("at", at), field("@message", message)) })
    .build()

class SyncFailuresTest :
    ExpectSpec({

        context("what counts as a device that could not sync") {
            expect("the requests of a sync round that were refused") {
                SyncFailures.counts(failed("POST", "/bands/b1/ops", 409, ApiErrors.BUSY)) shouldBe true
                SyncFailures.counts(failed("GET", "/bands/b1/state", 403, ApiErrors.FORBIDDEN)) shouldBe true
                SyncFailures.counts(failed("GET", "/me", 401, ApiErrors.SESSIONS_REVOKED, user = null)) shouldBe true
                SyncFailures.counts(failed("POST", "/session", 401, ApiErrors.UNAUTHORIZED, user = null)) shouldBe true
                SyncFailures.counts(failed("POST", "/bands/b1/attachments", 413, ApiErrors.BAND_TOO_LARGE)) shouldBe true
            }

            expect("any server error and any 'update required', whatever was asked") {
                SyncFailures.counts(failed("GET", "/bands/b1/log", 500, ApiErrors.INTERNAL)) shouldBe true
                SyncFailures.counts(failed("POST", "/invites/…/accept", 426, ApiErrors.UPDATE_REQUIRED, user = null)) shouldBe true
            }

            expect("not what the user tried and was refused, and not a band that is gone for them") {
                SyncFailures.counts(failed("POST", "/invites/…/accept", 409, ApiErrors.ALREADY_MEMBER)) shouldBe false
                SyncFailures.counts(failed("POST", "/bands/b1/leave", 409, ApiErrors.OWNER_CANNOT_LEAVE)) shouldBe false
                SyncFailures.counts(failed("GET", "/bands/b1/state", 404, ApiErrors.BAND_NOT_FOUND)) shouldBe false
                SyncFailures.counts(failed("GET", "/bands/b1/state", 404, ApiErrors.BAND_DELETED)) shouldBe false
            }

            expect("not a token that ran out, which the app renews by itself; only a refused renewal") {
                SyncFailures.counts(failed("GET", "/me", 401, ApiErrors.UNAUTHORIZED, user = null)) shouldBe false
                SyncFailures.counts(failed("POST", "/bands/b1/ops", 401, ApiErrors.UNAUTHORIZED, user = null)) shouldBe false
                SyncFailures.counts(failed("POST", "/session", 401, ApiErrors.UNAUTHORIZED, user = null)) shouldBe true
            }

            expect("not what the app takes as done: a file already uploaded, a cost that is gone, the place search down") {
                SyncFailures.counts(failed("POST", "/bands/b1/attachments", 409, ApiErrors.ATTACHMENT_EXISTS)) shouldBe false
                SyncFailures.counts(failed("POST", "/bands/b1/attachments", 404, ApiErrors.ATTACHMENT_NOT_FOUND)) shouldBe false
                SyncFailures.counts(failed("GET", "/bands/b1/places", 503, ApiErrors.PLACES_UNAVAILABLE)) shouldBe false
            }

            expect("not a request that went well, and not one that was not the app's") {
                SyncFailures.counts(failed("GET", "/me", 200)) shouldBe false
                val stranger = failed("GET", "/me", 401, ApiErrors.UNAUTHORIZED, user = null, installation = null, app = null)
                SyncFailures.counts(stranger) shouldBe false
                SyncFailures.counts(stranger.copy(path = "/wp-login.php", status = 500)) shouldBe false
            }
        }

        context("the failures per device") {
            expect("a device is a user's installation, the latest failure first, with its kinds counted") {
                val requests = listOf(
                    LoggedRequest(100, failed("POST", "/bands/b1/ops", 409, ApiErrors.BUSY, app = "249")),
                    LoggedRequest(300, failed("POST", "/bands/b2/ops", 409, ApiErrors.BUSY)),
                    LoggedRequest(200, failed("GET", "/bands/b1/state", 500, ApiErrors.INTERNAL)),
                    LoggedRequest(400, failed("GET", "/me", 426, ApiErrors.UPDATE_REQUIRED, "carol", installation = null, app = "11")),
                    LoggedRequest(250, failed("POST", "/invites/…/accept", 409, ApiErrors.ALREADY_MEMBER)),
                )

                val list = SyncFailures.grouped(requests, days = 3)

                list.available shouldBe true
                list.days shouldBe 3
                list.unidentified.shouldBeNull()
                list.devices.map { it.userId to it.installation } shouldContainExactly listOf("carol" to null, "bob" to "phone-1")
                val bob = list.devices[1]
                bob.count shouldBe 3
                bob.first shouldBe 100
                bob.last shouldBe 300
                bob.appVersion shouldBe "250"
                bob.kinds.map { "${it.status} ${it.error} x${it.count}" } shouldContainExactly listOf("409 busy x2", "500 internal x1")
                bob.kinds.first().request shouldBe "POST /bands/b2/ops"
                bob.kinds.first().last shouldBe 300
            }

            expect("two phones of one user are two devices, and their browsers one") {
                val requests = listOf(
                    LoggedRequest(1, failed("GET", "/me", 500, installation = "phone-1")),
                    LoggedRequest(2, failed("GET", "/me", 500, installation = "phone-2")),
                    LoggedRequest(3, failed("GET", "/me", 500, installation = null)),
                    LoggedRequest(4, failed("GET", "/me", 500, installation = null)),
                )

                SyncFailures.grouped(requests, 1).devices.map { it.installation to it.count } shouldContainExactly
                    listOf(null to 2, "phone-2" to 1, "phone-1" to 1)
            }

            expect("failures of nobody known are kept apart") {
                val requests = listOf(
                    LoggedRequest(1, failed("GET", "/me", 426, ApiErrors.UPDATE_REQUIRED, user = null, installation = null, app = "3")),
                    LoggedRequest(2, failed("GET", "/me", 426, ApiErrors.UPDATE_REQUIRED, user = null, installation = null, app = "3")),
                    // The deploy's own check, and any token that ran out: not failures at all.
                    LoggedRequest(3, failed("GET", "/me", 401, ApiErrors.UNAUTHORIZED, user = null, installation = null, app = "0")),
                )

                val list = SyncFailures.grouped(requests, 1)

                list.devices.shouldBeEmpty()
                list.unidentified!!.count shouldBe 2
            }
        }

        context("the log in memory") {
            expect("keeps the failed requests of the time asked for, and forgets the oldest past its size") {
                var now = 1000L
                val log = InMemoryRequestLog({ now }, capacity = 2)

                log.record(failed("GET", "/me", 200))
                log.record(failed("GET", "/me", 500))
                now = 2000
                log.record(failed("GET", "/me", 501))
                now = 3000
                log.record(failed("GET", "/me", 502))

                log.failed(0, 5000).map { it.line.status } shouldContainExactly listOf(502, 501)
                log.failed(0, 2500).map { it.at } shouldContainExactly listOf(2000L)
            }
        }

        context("the log in CloudWatch") {
            val line = failed("POST", "/bands/b1/ops", 409, ApiErrors.BUSY).format("s3=1 calls/2ms db=0 calls/0ms")

            expect("asks for the failed lines of the time, waits until the query is done, and reads them") {
                val logs = FakeLogs(
                    listOf(
                        results(QueryStatus.SCHEDULED),
                        results(QueryStatus.RUNNING),
                        // One good row, one line of an older format, one row without a time.
                        results(QueryStatus.COMPLETE, "1790000000123.0" to line, "1790000000456" to "TIMING GET /me 500 3ms", "x" to line),
                    ),
                )
                val pauses = mutableListOf<Long>()

                val log = CloudWatchRequestLog("/aws/lambda/api", logs, clock = { 0 }, pause = { pauses += it })

                val failed = log.failed(1_789_999_000_999, 1_790_000_000_999)

                failed.map { it.at } shouldContainExactly listOf(1_790_000_000_123)
                failed.single().line.userId shouldBe "bob"
                pauses.size shouldBe 2
                logs.started.single().let {
                    it.logGroupName() shouldBe "/aws/lambda/api"
                    it.startTime() shouldBe 1_789_999_000
                    it.endTime() shouldBe 1_790_000_001
                    it.queryString() shouldBe CloudWatchRequestLog.QUERY
                }
                logs.stopped.shouldBeEmpty()
            }

            expect("gives up a query that takes too long, and stops it") {
                val logs = FakeLogs(listOf(results(QueryStatus.RUNNING)))
                var now = 0L

                shouldThrow<RequestLogUnavailable> {
                    CloudWatchRequestLog("/aws/lambda/api", logs, 1000, clock = { now }, pause = { now += it }).failed(0, 1)
                }

                logs.stopped shouldContainExactly listOf("q1")
            }

            expect("says so when CloudWatch gives the query up") {
                val logs = FakeLogs(listOf(results(QueryStatus.FAILED)))

                shouldThrow<RequestLogUnavailable> { CloudWatchRequestLog("/aws/lambda/api", logs).failed(0, 1) }
            }

            expect("says so when CloudWatch refuses, and stops a query that was started") {
                val refusedAtOnce = FakeLogs(emptyList(), startFails = SdkClientException.create("Access denied"))
                val refusedLater = FakeLogs(emptyList(), resultsFail = SdkClientException.create("Throttled"))

                shouldThrow<RequestLogUnavailable> { CloudWatchRequestLog("/aws/lambda/api", refusedAtOnce).failed(0, 1) }
                shouldThrow<RequestLogUnavailable> { CloudWatchRequestLog("/aws/lambda/api", refusedLater).failed(0, 1) }

                refusedAtOnce.stopped.shouldBeEmpty()
                refusedLater.stopped shouldContainExactly listOf("q1")
            }

            expect("leaves out the lines of nobody at all, so scanners cannot fill the rows") {
                CloudWatchRequestLog.QUERY shouldContain "filter @message not like / user=- install=- app=- /"
                RequestLine("GET", "/wp-login.php", 404, 1).format("s3=0 calls/0ms") shouldContain " user=- install=- app=- "
            }
        }
    })
