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
import java.util.concurrent.atomic.AtomicLong
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
 *
 * ── Speed calculation accuracy ────────────────────────────────────────────────
 *
 * The authoritative measurement for both download and upload is the NDT7 server's
 * AppInfo frame:  speed (Mbps) = AppInfo.NumBytes * 8 / AppInfo.ElapsedTime(µs)
 * (since 1 bit/µs = 1 Mbps).  All client-side byte counters are used only as
 * live-display fallbacks until the first server frame arrives.
 *
 * Upload fallback accuracy:
 *   OkHttp's WebSocket.send() returns true when data is enqueued in the write
 *   buffer (max ~16 MB), NOT when it traverses the network.  A naïve bytes/elapsed
 *   calculation therefore over-reports upload speed by up to ~25 Mbps on a 30 Mbps
 *   link.  Instead, we track "post-saturation goodput": once the buffer first fills
 *   (send() returns false), every subsequent successful send() means exactly
 *   UPLOAD_CHUNK bytes just left the buffer onto the wire.  This gives a network-
 *   accurate rate without needing server feedback.
 */
class Ndt7SpeedTestEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : SpeedTestEngine {

    private val locateAdapter      by lazy { moshi.adapter(Ndt7LocateResponse::class.java) }
    private val measurementAdapter by lazy { moshi.adapter(Ndt7Measurement::class.java) }

    companion object {
        private const val LOCATE_URL            = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
        private const val NDT7_PROTOCOL         = "net.measurementlab.ndt.v7"
        private const val DL_KEY                = "wss:///ndt/v7/download"
        private const val UL_KEY                = "wss:///ndt/v7/upload"
        private const val UPLOAD_CHUNK          = 32_768                 // 32 KB per WebSocket frame
        private const val LATENCY_PHASE_MS      = 1_500L                 // show LATENCY UI for first 1.5 s
        private const val US_PER_MS             = 1_000L
        private const val LOCATE_RETRIES        = 3
        private const val LOCATE_RETRY_DELAY_MS = 2_000L
        // Grace period after the sender closes the socket, giving the server time
        // to deliver its final AppInfo measurement before we fall back to the client
        // estimate.  2 s handles high-latency (≥ 200 ms RTT) or congested links.
        private const val UPLOAD_GRACE_MS       = 2_000L
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

        val serverUuid = server.urls.keys
            .firstOrNull { it.contains("ndt7_test_id=") }
            ?.substringAfter("ndt7_test_id=")
            ?.substringBefore("&")

        Timber.i("NDT7: server=${server.machine}  uuid=$serverUuid")

        // ── 2. Download phase ─────────────────────────────────────────────────
        val dlDurationMs    = config.durationSeconds * 1_000L
        val downloadSamples = mutableListOf<SpeedSample>()
        var bytesDownloaded = 0L
        // FIX (Bug 4): record dlStart on the first binary frame, not before the
        // WebSocket handshake, so early client-side speed samples are not inflated
        // by connection setup time.
        val dlWallStart     = System.currentTimeMillis()   // used for duration limit only
        var dlDataStartMs   = 0L                           // set on first binary frame

        var latestTcp: Ndt7TcpInfo? = null
        var latestBbr: Ndt7BbrInfo? = null

        // The NDT7 server sends periodic text frames containing AppInfo.NumBytes and
        // AppInfo.ElapsedTime (µs). These are measured from the server's perspective and
        // are the most accurate throughput measurement per the NDT7 spec:
        //   speed (Mbps) = numBytes * 8 / elapsedTime(µs)
        //                — because 1 bit/µs = 1 Mbit/s = 1 Mbps
        var latestAppInfoDl: Ndt7AppInfo? = null
        var lastDlSpeedMbps = 0.0
        // Rate-limit UI emissions during download to 200 ms intervals.
        // Binary frames can arrive at 60–100 Hz on fast links; emitting on every frame
        // causes the UI to receive noisy, rapidly-oscillating speed values that make
        // the gauge needle appear jumpy.  Byte accounting still happens on every frame.
        var lastDlEmitMs = 0L

        send(EngineProgress(ActiveTestState.Running(
            phase = TestPhase.LATENCY, progressFraction = 0f, elapsedMs = 0
        )))

        openDownloadWebSocket(downloadUrl, dlDurationMs).collect { event ->
            val wallElapsed = System.currentTimeMillis() - dlWallStart
            when (event) {
                is WsEvent.Binary -> {
                    // Record when data first starts flowing (after handshake).
                    if (dlDataStartMs == 0L) dlDataStartMs = System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - dlDataStartMs

                    bytesDownloaded += event.byteCount

                    // Always accumulate samples for the final result chart.
                    val fraction = (wallElapsed.toFloat() / dlDurationMs).coerceIn(0f, 1f)
                    val speedMbps = if (lastDlSpeedMbps > 0) lastDlSpeedMbps
                        else if (elapsed > 0) bytesDownloaded * 8.0 / elapsed / 1_000.0 else 0.0
                    downloadSamples += SpeedSample(elapsed, bytesDownloaded, speedMbps)

                    // Only push a UI update every 200 ms — eliminates high-frequency
                    // oscillation that makes the gauge needle look step-based / jumpy.
                    if (wallElapsed - lastDlEmitMs < 200L) return@collect
                    lastDlEmitMs = wallElapsed

                    val latMs       = latestTcp?.minRtt?.let { (it / US_PER_MS).toInt() }
                    val retransRate = latestTcp?.let { tcp ->
                        val sent    = tcp.bytesSent ?: 0L
                        val retrans = tcp.bytesRetrans ?: 0L
                        if (sent > 0) retrans.toDouble() / sent.toDouble() else null
                    }

                    val phase = if (wallElapsed < LATENCY_PHASE_MS) TestPhase.LATENCY else TestPhase.DOWNLOAD
                    val phaseFraction = if (wallElapsed < LATENCY_PHASE_MS)
                        wallElapsed.toFloat() / LATENCY_PHASE_MS else fraction

                    send(EngineProgress(ActiveTestState.Running(
                        phase            = phase,
                        progressFraction = phaseFraction,
                        elapsedMs        = wallElapsed,
                        latencyMs        = latMs,
                        downloadMbps     = speedMbps,
                        downloadSamples  = downloadSamples.toList(),
                        retransmitRate   = retransRate
                    )))
                }
                is WsEvent.Text -> {
                    parseMeasurement(event.payload)?.let { m ->
                        m.tcpInfo?.let { latestTcp = it }
                        m.bbrInfo?.let { latestBbr = it }
                        m.appInfo?.let { ai ->
                            if (ai.numBytes > 0 && ai.elapsedTime > 0) {
                                latestAppInfoDl = ai
                                // numBytes * 8 bits / elapsedTime µs = bits/µs = Mbps
                                lastDlSpeedMbps = ai.numBytes * 8.0 / ai.elapsedTime
                                Timber.v("NDT7 DL server: ${ai.numBytes}B in ${ai.elapsedTime}µs → %.1f Mbps".format(lastDlSpeedMbps))
                            }
                        }
                    }
                }
                is WsEvent.Failed -> throw event.cause
                else -> Unit
            }
        }

        // Final download speed: last server AppInfo (authoritative cumulative average).
        // Fallback: delta of client bytes between the 25 % and 100 % marks (skips slow-start).
        val avgDownloadMbps = latestAppInfoDl
            ?.let { ai -> ai.numBytes * 8.0 / ai.elapsedTime }
            ?: run {
                val skipCount = maxOf(1, downloadSamples.size / 4)
                if (downloadSamples.size > skipCount) {
                    val s0 = downloadSamples[skipCount - 1]
                    val s1 = downloadSamples.last()
                    val byteDelta = s1.cumulativeBytesTransferred - s0.cumulativeBytesTransferred
                    val timeDelta = s1.elapsedMs - s0.elapsedMs
                    if (timeDelta > 0) byteDelta * 8.0 / timeDelta / 1_000.0
                    else s1.instantSpeedMbps
                } else downloadSamples.lastOrNull()?.instantSpeedMbps ?: 0.0
            }

        val latencyMs = (latestTcp?.minRtt?.let { it / US_PER_MS } ?: 0L).toInt()
        val jitterMs  = (latestTcp?.rttVar?.let { it / US_PER_MS } ?: 0L).toInt()

        Timber.d("NDT7: DL avg=%.1f Mbps  latency=${latencyMs}ms  bytes=$bytesDownloaded".format(avgDownloadMbps))

        val dlTcp = latestTcp
        val dlBbr = latestBbr
        latestTcp = null
        latestBbr = null

        // ── 3. Upload phase ───────────────────────────────────────────────────
        val ulDurationMs  = (config.durationSeconds * 500L).coerceAtLeast(5_000L)
        val uploadSamples = mutableListOf<SpeedSample>()
        val ulStart       = System.currentTimeMillis()

        // Server sends text frames with AppInfo.NumBytes = bytes received by server.
        // This is the authoritative upload speed (bytes * 8 / elapsedTime µs → Mbps).
        var latestAppInfoUl: Ndt7AppInfo? = null
        var lastUlServerSpeedMbps: Double? = null

        // Counts bytes queued into OkHttp's write buffer. Because OkHttp buffers up to
        // ~16 MB ahead of the network, this can OVERCOUNT by up to 16 MB on slow links.
        // Never use this directly as the final speed result — it is only kept for the
        // bytesUploaded data-usage metric stored in the database.
        val bytesSentCounter = AtomicLong(0L)

        // Wall-clock time of the first successful send() call.
        // Using this as the time denominator (not ulStart) removes WebSocket connection
        // setup latency (~200–500 ms) from the unsaturated-path speed calculation,
        // preventing a systematic undercount in the early seconds of upload.
        // Timestamp of the very first successful send() call.
        // Using this (rather than ulStart) as the time base for unsaturated-path speed
        // calculations eliminates the WebSocket connection setup time from the denominator,
        // preventing a systematic undercount of upload speed in the early seconds.
        val ulDataStartMs = AtomicLong(0L)

        // Post-saturation goodput tracking.
        //
        // Once OkHttp's write buffer first fills up (send() returns false), the buffer
        // is at capacity.  From that point, every successful send() means exactly
        // UPLOAD_CHUNK bytes just left the buffer onto the wire — so the rate of
        // successful sends after saturation equals the true network throughput.
        //
        // networkGoodputStartMs: wall-clock time of first buffer saturation (0 = unsaturated)
        // networkGoodputBytes:   bytes sent successfully AFTER saturation begins
        val networkGoodputStartMs = AtomicLong(0L)
        val networkGoodputBytes   = AtomicLong(0L)

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

        // Sender — waits for socket open then pumps 32 KB chunks as fast as allowed.
        // Tracks post-saturation bytes for accurate fallback speed calculation.
        val senderJob = launch(Dispatchers.IO) {
            val chunk    = ByteArray(UPLOAD_CHUNK).also { Random.Default.nextBytes(it) }.toByteString()
            val deadline = System.currentTimeMillis() + 3_000L
            while (!isOpen.get() && System.currentTimeMillis() < deadline) delay(20)

            val endMs = System.currentTimeMillis() + ulDurationMs
            while (System.currentTimeMillis() < endMs) {
                if (ulWs.send(chunk)) {
                    // Record the timestamp of the first successful send so the progress
                    // emitter can use actual network-send elapsed time, not ulStart.
                    ulDataStartMs.compareAndSet(0L, System.currentTimeMillis())
                    bytesSentCounter.addAndGet(UPLOAD_CHUNK.toLong())
                    // If the buffer has previously saturated, this send() means
                    // UPLOAD_CHUNK bytes just flushed through the network.
                    if (networkGoodputStartMs.get() > 0L) {
                        networkGoodputBytes.addAndGet(UPLOAD_CHUNK.toLong())
                    }
                } else {
                    // Back-pressure: OkHttp write buffer is full.
                    // Record the first saturation time (compareAndSet ensures it's set once).
                    networkGoodputStartMs.compareAndSet(0L, System.currentTimeMillis())
                    delay(5)
                }
            }
            ulWs.close(1000, "upload complete")
        }

        // Timer-based progress emitter — runs every 300 ms independent of server messages.
        val progressJob = launch {
            while (true) {
                delay(300)
                val elapsed   = System.currentTimeMillis() - ulStart
                // Keep emitting through the full grace window so the gauge stays live
                // while the server delivers its final AppInfo before we fall back.
                if (elapsed > ulDurationMs + UPLOAD_GRACE_MS) break

                // Priority order for live upload speed display:
                //
                // 1. Server AppInfo (most accurate — bytes the server actually received).
                // 2. Post-saturation goodput: after OkHttp's write buffer first fills,
                //    every successful send() = UPLOAD_CHUNK bytes left the buffer onto
                //    the wire, so rate-of-sends reflects real network throughput.
                // 3. Raw bytesSentCounter / elapsed — valid ONLY when the buffer has
                //    never saturated, meaning OkHttp is not queuing ahead of the network.
                //    On fast links (WiFi / high-bandwidth) the buffer may never fill, so
                //    this is the only live estimate available.  Skip the first 800 ms so
                //    TCP slow-start does not skew the early reading.
                val speedMbps: Double? = lastUlServerSpeedMbps ?: run {
                    val satStart = networkGoodputStartMs.get()
                    if (satStart > 0L) {
                        // Post-saturation path: buffer has filled at least once.
                        val goodput    = networkGoodputBytes.get()
                        val satElapsed = elapsed - (satStart - ulStart)
                        if (satElapsed > 0L && goodput > 0L)
                            goodput * 8.0 / satElapsed / 1_000.0
                        else null
                    } else {
                        // Unsaturated path: buffer is keeping up — bytesSentCounter is accurate.
                        // Use ulDataStartMs (time of first send) not ulStart (before socket open)
                        // so connection setup latency doesn't deflate the speed estimate.
                        val dataStart = ulDataStartMs.get()
                        val bytes     = bytesSentCounter.get()
                        if (dataStart > 0L && bytes > 0L) {
                            val sendElapsed = System.currentTimeMillis() - dataStart
                            if (sendElapsed > 800L)
                                bytes * 8.0 / sendElapsed / 1_000.0
                            else null  // skip first 800 ms of TCP slow-start
                        } else null  // socket not yet open
                    }
                }

                val fraction    = (elapsed.toFloat() / ulDurationMs).coerceIn(0f, 1f)
                val retransRate = latestTcp?.let { tcp ->
                    val s = tcp.bytesSent ?: 0L; val r = tcp.bytesRetrans ?: 0L
                    if (s > 0) r.toDouble() / s.toDouble() else null
                }
                uploadSamples += SpeedSample(elapsed, bytesSentCounter.get(), speedMbps ?: 0.0)
                send(EngineProgress(ActiveTestState.Running(
                    phase            = TestPhase.UPLOAD,
                    progressFraction = fraction,
                    elapsedMs        = elapsed,
                    latencyMs        = latencyMs,
                    downloadMbps     = avgDownloadMbps,
                    uploadMbps       = speedMbps,
                    uploadSamples    = uploadSamples.toList(),
                    retransmitRate   = retransRate
                )))
            }
        }

        // Server measurement listener — captures AppInfo for authoritative upload speed.
        // UPLOAD_GRACE_MS = 2 s gives the server time to deliver its final AppInfo
        // on high-latency or congested links before we fall back to the client estimate.
        try {
            for (event in ulChannel) {
                val elapsed = System.currentTimeMillis() - ulStart
                when (event) {
                    is WsEvent.Text -> {
                        parseMeasurement(event.payload)?.let { m ->
                            m.tcpInfo?.let { latestTcp = it }
                            m.bbrInfo?.let { latestBbr = it }
                            m.appInfo?.let { ai ->
                                if (ai.numBytes > 0 && ai.elapsedTime > 0) {
                                    latestAppInfoUl = ai
                                    lastUlServerSpeedMbps = ai.numBytes * 8.0 / ai.elapsedTime
                                    Timber.v("NDT7 UL server: ${ai.numBytes}B in ${ai.elapsedTime}µs → %.1f Mbps".format(lastUlServerSpeedMbps))
                                    if (ai.numBytes > bytesSentCounter.get()) {
                                        bytesSentCounter.set(ai.numBytes)
                                    }
                                }
                            }
                        }
                    }
                    is WsEvent.Failed -> {
                        progressJob.cancel()
                        senderJob.cancel()
                        throw event.cause
                    }
                    is WsEvent.Closed -> break
                    else -> Unit
                }
                if (elapsed >= ulDurationMs + UPLOAD_GRACE_MS) break
            }
        } finally {
            progressJob.cancel()
            senderJob.cancel()
            ulWs.cancel()
        }

        // Final upload speed: server's last AppInfo measurement is authoritative.
        // It measures bytes that actually traversed the network and is immune to
        // OkHttp write-buffer overcounting.
        //
        // FIX (Bug 1): If server AppInfo never arrived, use post-saturation goodput
        // instead of bytesSentCounter / totalTime, which inflates results by up to
        // 16 MB (OkHttp's max write-queue size) on slower connections.
        val avgUploadMbps = latestAppInfoUl
            ?.let { ai -> ai.numBytes * 8.0 / ai.elapsedTime }
            ?: run {
                val satStart = networkGoodputStartMs.get()
                if (satStart > 0L) {
                    // Post-saturation rate = bytes sent after buffer filled / duration since fill.
                    // This reflects actual network throughput, not buffer fill rate.
                    val goodput    = networkGoodputBytes.get()
                    val satElapsed = ulDurationMs - (satStart - ulStart)
                    if (satElapsed > 0L && goodput > 0L)
                        goodput * 8.0 / satElapsed / 1_000.0
                    else bytesSentCounter.get() * 8.0 / ulDurationMs / 1_000.0
                } else {
                    // Buffer never saturated: the network kept pace with the sender.
                    // In this case bytesSentCounter is not inflated — use it directly.
                    bytesSentCounter.get() * 8.0 / ulDurationMs / 1_000.0
                }
            }

        val finalTcp    = latestTcp ?: dlTcp
        val finalBbr    = latestBbr ?: dlBbr
        val totalDurSec = ((System.currentTimeMillis() - dlWallStart) / 1_000).toInt()

        val retransmitRate = finalTcp?.let { tcp ->
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
            bytesUploaded    = bytesSentCounter.get(),
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