package com.nybroadband.mobile.engine

import com.nybroadband.mobile.domain.model.ActiveTestState
import com.nybroadband.mobile.domain.model.EngineComplete
import com.nybroadband.mobile.domain.model.EngineProgress
import com.nybroadband.mobile.domain.model.EngineUpdate
import com.nybroadband.mobile.domain.model.RawTestResult
import com.nybroadband.mobile.domain.model.SpeedSample
import com.nybroadband.mobile.domain.model.TestConfig
import com.nybroadband.mobile.domain.model.TestPhase
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// NDT7 JSON models  (Moshi code-gen adapters generated at compile time via KSP)
// ─────────────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class Ndt7LocateResponse(
    @Json(name = "results") val results: List<Ndt7Server> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Ndt7Server(
    @Json(name = "machine")  val machine:  String                = "",
    @Json(name = "location") val location: Ndt7ServerLocation?   = null,
    @Json(name = "urls")     val urls:     Map<String, String>   = emptyMap()
)

@JsonClass(generateAdapter = true)
data class Ndt7ServerLocation(
    @Json(name = "city")    val city:    String? = null,
    @Json(name = "country") val country: String? = null
)

@JsonClass(generateAdapter = true)
data class Ndt7Measurement(
    @Json(name = "AppInfo") val appInfo: Ndt7AppInfo? = null,
    @Json(name = "TCPInfo") val tcpInfo: Ndt7TcpInfo? = null,
    @Json(name = "BBRInfo") val bbrInfo: Ndt7BbrInfo? = null
)

@JsonClass(generateAdapter = true)
data class Ndt7AppInfo(
    @Json(name = "ElapsedTime") val elapsedTime: Long = 0,  // microseconds
    @Json(name = "NumBytes")    val numBytes:    Long = 0
)

/** All RTT / retransmit metrics from the kernel's TCP_INFO socket option. */
@JsonClass(generateAdapter = true)
data class Ndt7TcpInfo(
    @Json(name = "MinRTT")       val minRtt:       Long? = null,  // µs — minimum RTT observed
    @Json(name = "RTT")          val rtt:          Long? = null,  // µs — smoothed mean RTT
    @Json(name = "RTTVar")       val rttVar:       Long? = null,  // µs — RTT variance (jitter)
    @Json(name = "BytesSent")    val bytesSent:    Long? = null,
    @Json(name = "BytesAcked")   val bytesAcked:   Long? = null,
    @Json(name = "BytesRetrans") val bytesRetrans: Long? = null,  // retransmitted bytes
    @Json(name = "ElapsedTime")  val elapsedTime:  Long? = null
)

/** BBR (Bottleneck Bandwidth and RTT) congestion-control metrics. */
@JsonClass(generateAdapter = true)
data class Ndt7BbrInfo(
    @Json(name = "BW")     val bandwidthBps: Long? = null,  // bits/sec
    @Json(name = "MinRTT") val minRtt:       Long? = null   // µs
)

// ─────────────────────────────────────────────────────────────────────────────
// Internal WebSocket event types
// ─────────────────────────────────────────────────────────────────────────────

private sealed class WsEvent {
    data class Text(val payload: String) : WsEvent()
    data class Binary(val byteCount: Long) : WsEvent()
    data class Closed(val code: Int, val reason: String) : WsEvent()
    data class Failed(val cause: Throwable) : WsEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Real NDT7 speed test engine.
 *
 * Protocol: https://www.measurementlab.net/tests/ndt/ndt7/
 *
 * Flow:
 *   1. Discover the nearest M-Lab NDT7 server via the Locate v2 API.
 *   2. Open a download WebSocket for [TestConfig.durationSeconds] seconds.
 *      – Binary frames: count bytes for throughput calculation.
 *      – Text frames: JSON measurement from server (TCPInfo, BBRInfo).
 *      – Emit [TestPhase.LATENCY] UI during the first 1.5 s (MinRTT from TCPInfo).
 *      – Emit [TestPhase.DOWNLOAD] for the remainder.
 *   3. Open an upload WebSocket for half the download duration (min 5 s).
 *      – Send random binary chunks; parse server measurement text frames.
 *   4. Emit [EngineComplete] with averaged metrics.
 *
 * Cancellation: cancelling the collecting coroutine cancels both WebSockets cleanly
 * via [awaitClose].
 */
class Ndt7SpeedTestEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : SpeedTestEngine {

    private val locateAdapter    by lazy { moshi.adapter(Ndt7LocateResponse::class.java) }
    private val measurementAdapter by lazy { moshi.adapter(Ndt7Measurement::class.java) }

    companion object {
        private const val LOCATE_URL       = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
        private const val NDT7_PROTOCOL    = "net.measurementlab.ndt.v7"
        private const val DL_KEY           = "wss:///ndt/v7/download"
        private const val UL_KEY           = "wss:///ndt/v7/upload"
        private const val UPLOAD_CHUNK     = 32_768                 // 32 KB per WebSocket frame
        private const val LATENCY_PHASE_MS = 1_500L                 // show LATENCY UI for first 1.5 s
        private const val US_PER_MS        = 1_000L
        private const val LOCATE_RETRIES   = 3                      // attempts before giving up
        private const val LOCATE_RETRY_DELAY_MS = 2_000L            // wait between retries
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun execute(config: TestConfig): Flow<EngineUpdate> = channelFlow {

        // ── 1. Locate nearest NDT7 server ─────────────────────────────────────
        val server = locateServer()
        val serverName     = server.location?.city ?: server.machine.substringBefore(".")
        val serverLocation = server.location?.let { "${it.city.orEmpty()}, ${it.country.orEmpty()}" }

        val downloadUrl = server.urls.entries
            .firstOrNull { it.key.startsWith("$DL_KEY?") }?.value
            ?: server.urls[DL_KEY]
            ?: throw IOException("NDT7: no download URL in locate response")

        val uploadUrl = server.urls.entries
            .firstOrNull { it.key.startsWith("$UL_KEY?") }?.value
            ?: server.urls[UL_KEY]
            ?: throw IOException("NDT7: no upload URL in locate response")

        // Extract NDT7 test UUID (shared across download + upload)
        val serverUuid = server.urls.keys
            .firstOrNull { it.contains("ndt7_test_id=") }
            ?.substringAfter("ndt7_test_id=")
            ?.substringBefore("&")

        Timber.i("NDT7: server=${server.machine}  uuid=$serverUuid")

        // ── 2. Download phase ─────────────────────────────────────────────────
        val dlDurationMs    = config.durationSeconds * 1_000L
        val downloadSamples = mutableListOf<SpeedSample>()
        var bytesDownloaded = 0L
        val dlStart         = System.currentTimeMillis()

        var latestTcp: Ndt7TcpInfo? = null
        var latestBbr: Ndt7BbrInfo? = null

        // Emit initial LATENCY frame so the UI transitions immediately
        send(EngineProgress(ActiveTestState.Running(
            phase = TestPhase.LATENCY, progressFraction = 0f, elapsedMs = 0
        )))

        openDownloadWebSocket(downloadUrl, dlDurationMs).collect { event ->
            val elapsed = System.currentTimeMillis() - dlStart
            when (event) {
                is WsEvent.Binary -> {
                    bytesDownloaded += event.byteCount
                    val fraction    = (elapsed.toFloat() / dlDurationMs).coerceIn(0f, 1f)
                    val speedMbps   = if (elapsed > 0) bytesDownloaded * 8.0 / elapsed / 1_000.0 else 0.0
                    val latMs       = latestTcp?.minRtt?.let { (it / US_PER_MS).toInt() }

                    downloadSamples += SpeedSample(elapsed, bytesDownloaded, speedMbps)

                    val phase = if (elapsed < LATENCY_PHASE_MS) TestPhase.LATENCY else TestPhase.DOWNLOAD
                    val phaseFraction = if (elapsed < LATENCY_PHASE_MS)
                        elapsed.toFloat() / LATENCY_PHASE_MS else fraction

                    send(EngineProgress(ActiveTestState.Running(
                        phase            = phase,
                        progressFraction = phaseFraction,
                        elapsedMs        = elapsed,
                        latencyMs        = latMs,
                        downloadMbps     = speedMbps,
                        downloadSamples  = downloadSamples.toList()
                    )))
                }
                is WsEvent.Text -> {
                    parseMeasurement(event.payload)?.let { m ->
                        m.tcpInfo?.let { latestTcp = it }
                        m.bbrInfo?.let { latestBbr = it }
                    }
                }
                is WsEvent.Failed -> throw event.cause
                else -> Unit
            }
        }

        // Average speed — skip first 25 % (TCP slow-start ramp)
        val skipCount       = maxOf(1, downloadSamples.size / 4)
        val avgDownloadMbps = if (downloadSamples.size > skipCount)
            downloadSamples.drop(skipCount).map { it.instantSpeedMbps }.average()
        else downloadSamples.lastOrNull()?.instantSpeedMbps ?: 0.0

        val latencyMs = (latestTcp?.minRtt?.let { it / US_PER_MS } ?: 0L).toInt()
        val jitterMs  = (latestTcp?.rttVar?.let { it / US_PER_MS } ?: 0L).toInt()

        Timber.d("NDT7: DL avg=%.1f Mbps  latency=${latencyMs}ms  bytes=$bytesDownloaded".format(avgDownloadMbps))

        // Capture DL TCP/BBR for result (upload will overwrite latestTcp/Bbr)
        val dlTcp = latestTcp
        val dlBbr = latestBbr
        latestTcp = null
        latestBbr = null

        // ── 3. Upload phase ───────────────────────────────────────────────────
        val ulDurationMs    = (config.durationSeconds * 500L).coerceAtLeast(5_000L)
        val uploadSamples   = mutableListOf<SpeedSample>()
        var bytesUploaded   = 0L
        val ulStart         = System.currentTimeMillis()

        // The upload WebSocket opens, client sends data, server replies with measurements.
        // We use a Channel internally so the sender runs as a sibling coroutine.
        val ulChannel = Channel<WsEvent>(Channel.UNLIMITED)
        val isOpen    = AtomicBoolean(false)
        val ulRequest = Request.Builder()
            .url(uploadUrl)
            .header("Sec-WebSocket-Protocol", NDT7_PROTOCOL)
            .build()

        val ulWs = okHttpClient.newWebSocket(ulRequest, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isOpen.set(true)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                ulChannel.trySend(WsEvent.Text(text))
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
                ulChannel.trySend(WsEvent.Closed(code, reason))
                ulChannel.close()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                ulChannel.trySend(WsEvent.Failed(t))
                ulChannel.close(t)
            }
        })

        // Sender coroutine — waits for socket open then pumps chunks
        val senderJob = launch(Dispatchers.IO) {
            val chunk = ByteArray(UPLOAD_CHUNK).also { Random.Default.nextBytes(it) }
                .toByteString()
            // Wait up to 3 s for the WebSocket to open
            val deadline = System.currentTimeMillis() + 3_000L
            while (!isOpen.get() && System.currentTimeMillis() < deadline) delay(20)

            val endMs = System.currentTimeMillis() + ulDurationMs
            while (System.currentTimeMillis() < endMs) {
                if (!ulWs.send(chunk)) delay(5) // back-pressure: buffer full
            }
            ulWs.close(1000, "upload complete")
        }

        try {
            for (event in ulChannel) {
                val elapsed = System.currentTimeMillis() - ulStart
                when (event) {
                    is WsEvent.Text -> {
                        parseMeasurement(event.payload)?.let { m ->
                            m.tcpInfo?.let { latestTcp = it }
                            m.bbrInfo?.let { latestBbr = it }
                            // Use server-reported byte count for accurate upload speed.
                            // bytesUploaded from the sender side is not tracked here because
                            // the sender runs on a separate coroutine; using appInfo.numBytes
                            // (bytes the server actually received) is more accurate anyway.
                            m.appInfo?.let { if (it.numBytes > 0) bytesUploaded = it.numBytes }
                        }
                        val fraction  = (elapsed.toFloat() / ulDurationMs).coerceIn(0f, 1f)
                        val speedMbps = if (elapsed > 0) bytesUploaded * 8.0 / elapsed / 1_000.0 else 0.0
                        uploadSamples += SpeedSample(elapsed, bytesUploaded, speedMbps)

                        send(EngineProgress(ActiveTestState.Running(
                            phase            = TestPhase.UPLOAD,
                            progressFraction = fraction,
                            elapsedMs        = elapsed,
                            latencyMs        = latencyMs,
                            downloadMbps     = avgDownloadMbps,
                            uploadMbps       = speedMbps,
                            uploadSamples    = uploadSamples.toList()
                        )))
                    }
                    is WsEvent.Failed -> {
                        senderJob.cancel()
                        throw event.cause
                    }
                    is WsEvent.Closed -> break
                    else -> Unit
                }
                // Stop collecting once our duration has elapsed
                if (elapsed >= ulDurationMs + 500) break
            }
        } finally {
            senderJob.cancel()
            ulWs.cancel()
        }

        // Upload speed: use server-reported bytes from last measurement, or sender count
        val avgUploadMbps = run {
            val skip = maxOf(1, uploadSamples.size / 4)
            if (uploadSamples.size > skip)
                uploadSamples.drop(skip).map { it.instantSpeedMbps }.average()
            else uploadSamples.lastOrNull()?.instantSpeedMbps ?: 0.0
        }

        // Prefer UL TCP metrics for RTT (more stable during data transfer)
        val finalTcp    = latestTcp ?: dlTcp
        val finalBbr    = latestBbr ?: dlBbr
        val totalDurSec = ((System.currentTimeMillis() - dlStart) / 1_000).toInt()

        val retransmitRate = finalTcp
            ?.let { tcp ->
                val sent    = tcp.bytesSent ?: 0L
                val retrans = tcp.bytesRetrans ?: 0L
                if (sent > 0) retrans.toDouble() / sent.toDouble() else null
            }

        Timber.i(
            "NDT7 complete: DL=%.1f UL=%.1f latency=${latencyMs}ms jitter=${jitterMs}ms " +
            "retransmit=%.2f%%".format(avgDownloadMbps, avgUploadMbps, (retransmitRate ?: 0.0) * 100)
        )

        send(EngineComplete(RawTestResult(
            downloadMbps     = avgDownloadMbps,
            uploadMbps       = avgUploadMbps,
            latencyMs        = latencyMs,
            jitterMs         = jitterMs,
            bytesDownloaded  = bytesDownloaded,
            bytesUploaded    = bytesUploaded,
            testDurationSec  = totalDurSec,
            serverName       = serverName,
            serverLocation   = serverLocation,
            minRttUs         = finalTcp?.minRtt,
            meanRttUs        = finalTcp?.rtt,
            rttVarUs         = finalTcp?.rttVar,
            retransmitRate   = retransmitRate,
            bbrBandwidthBps  = finalBbr?.bandwidthBps,
            bbrMinRttUs      = finalBbr?.minRtt,
            serverUuid       = serverUuid
        )))

    }.flowOn(Dispatchers.IO)

    // ── Server discovery ──────────────────────────────────────────────────────

    /**
     * Finds the nearest NDT7 server via the M-Lab Locate v2 API.
     *
     * Retries up to [LOCATE_RETRIES] times with a [LOCATE_RETRY_DELAY_MS] pause between
     * attempts to handle transient DNS failures (UnknownHostException) or flaky
     * connectivity at test start time.
     *
     * Throws the last exception if all attempts fail.
     */
    private suspend fun locateServer(): Ndt7Server {
        var lastException: Exception? = null
        repeat(LOCATE_RETRIES) { attempt ->
            try {
                val request  = Request.Builder().url(LOCATE_URL).build()
                val response = okHttpClient.newCall(request).execute()
                response.use { r ->
                    if (!r.isSuccessful) throw IOException("NDT7 locate failed: HTTP ${r.code}")
                    val body = r.body?.string()
                        ?: throw IOException("NDT7 locate: empty response body")
                    val parsed = locateAdapter.fromJson(body)
                        ?: throw IOException("NDT7 locate: could not parse response")
                    return parsed.results.firstOrNull()
                        ?: throw IOException("NDT7 locate: no servers returned")
                }
            } catch (e: UnknownHostException) {
                lastException = e
                Timber.w("NDT7 locate attempt ${attempt + 1}/$LOCATE_RETRIES failed (DNS): ${e.message}")
                if (attempt < LOCATE_RETRIES - 1) delay(LOCATE_RETRY_DELAY_MS)
            } catch (e: IOException) {
                lastException = e
                Timber.w("NDT7 locate attempt ${attempt + 1}/$LOCATE_RETRIES failed: ${e.message}")
                if (attempt < LOCATE_RETRIES - 1) delay(LOCATE_RETRY_DELAY_MS)
            }
        }
        throw lastException ?: IOException("NDT7 locate: all $LOCATE_RETRIES attempts failed")
    }

    // ── Download WebSocket ────────────────────────────────────────────────────

    /**
     * Opens a download WebSocket and emits events until [durationMs] elapses.
     * A timeout coroutine closes the socket after the duration — the resulting
     * [WsEvent.Closed] terminates the flow.
     */
    private fun openDownloadWebSocket(url: String, durationMs: Long): Flow<WsEvent> =
        callbackFlow {
            val request = Request.Builder()
                .url(url)
                .header("Sec-WebSocket-Protocol", NDT7_PROTOCOL)
                .build()

            val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    trySend(WsEvent.Text(text))
                }
                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    trySend(WsEvent.Binary(bytes.size.toLong()))
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, reason)
                    close()
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    trySend(WsEvent.Failed(t))
                    close(t)
                }
            })

            // Auto-close after the test duration
            launch {
                delay(durationMs)
                ws.close(1000, "duration elapsed")
            }

            awaitClose { ws.cancel() }
        }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseMeasurement(json: String): Ndt7Measurement? = try {
        measurementAdapter.fromJson(json)
    } catch (e: Exception) {
        Timber.w("NDT7: could not parse measurement JSON: ${e.message}")
        null
    }
}
