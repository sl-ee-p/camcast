package com.slp.camcast.server

import com.slp.camcast.camera.FrameBuffer
import com.slp.camcast.clips.ClipReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MJPEG HTTP server: `/video`, `/snapshot.jpg` and a status page at `/`, all behind basic auth.
 * Plain sockets, one thread per client, at most [maxClients] at once. [stop] closes everything.
 */
class CamServer(
    private val port: Int,
    private val frames: FrameBuffer,
    private val credentials: Credentials,
    private val maxClients: Int = 6,
    /** How long `/snapshot.jpg` waits for a first frame before answering 503. */
    private val snapshotWaitMillis: Long = 3_000,
    private val requestTimeoutMillis: Int = 10_000,
    /**
     * A `/video` client whose frame write blocks this long is dropped. Socket writes have no timeout, so a viewer
     * that vanished without closing (Wi-Fi gone, power cut) would otherwise hold its slot until TCP gives up (~15 min).
     */
    private val writeStallMillis: Long = 20_000,
    /** Motion clips, or null when recording is off (then the clip paths are 404). */
    private val clips: ClipSource? = null,
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val clients = Collections.synchronizedSet(HashSet<Socket>())
    private val activeClients = AtomicInteger()
    private val streamingClients = AtomicInteger()
    private val writingSince = ConcurrentHashMap<Socket, Long>()
    private var reaper: ScheduledExecutorService? = null

    /** Clients currently watching `/video`. */
    val streamers: Int get() = streamingClients.get()

    /** Binds the port (0 picks a free one) and starts accepting. Returns the bound port. */
    fun start(): Int {
        check(!running) { "already running" }
        require(credentials.isUsable) { "username and password must be set" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        executor = Executors.newCachedThreadPool()
        running = true
        reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "camcast-reaper").apply { isDaemon = true } }
            .apply {
                val period = (writeStallMillis / 4).coerceAtLeast(10)
                scheduleWithFixedDelay(::dropStalledClients, period, period, TimeUnit.MILLISECONDS)
            }
        Thread(::acceptLoop, "camcast-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        executor?.shutdownNow()
        executor = null
        reaper?.shutdownNow()
        reaper = null
    }

    private fun dropStalledClients() {
        val limit = System.nanoTime() - writeStallMillis * 1_000_000L
        writingSince.forEach { (socket, since) -> if (since < limit) runCatching { socket.close() } }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            if (activeClients.incrementAndGet() > maxClients) {
                activeClients.decrementAndGet()
                rejectBusy(client)
                continue
            }
            clients += client
            try {
                executor?.execute { handle(client) } ?: client.close()
            } catch (e: RuntimeException) {
                clients -= client
                activeClients.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun rejectBusy(client: Socket) {
        runCatching {
            client.use { HttpResponse.writeText(it.getOutputStream(), 503, "Service Unavailable") }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                it.soTimeout = requestTimeoutMillis
                it.tcpNoDelay = true
                serve(it)
            }
        } catch (e: IOException) {
            // Client went away or timed out; nothing to answer.
        } finally {
            clients -= client
            activeClients.decrementAndGet()
        }
    }

    private fun serve(client: Socket) {
        val out = client.getOutputStream()
        val request = when (val parsed = RequestParser.parse(client.getInputStream().buffered())) {
            is ParseResult.Closed -> return
            is ParseResult.Bad -> {
                HttpResponse.writeText(out, parsed.status, parsed.reason)
                return
            }
            is ParseResult.Ok -> parsed.request
        }
        val head = request.method == "HEAD"
        // Auth comes before routing so an unauthenticated client cannot tell which paths exist.
        if (!BasicAuth.matches(request.headers["authorization"], credentials)) {
            HttpResponse.writeUnauthorized(out, !head)
            return
        }
        when (request.path) {
            "/" -> statusPage(out, !head)
            "/snapshot.jpg" -> snapshot(out, !head)
            "/video" -> video(client, out, head)
            "/events.json" -> events(out, !head)
            else -> clipRoute(client, out, request.path, head)
        }
    }

    private fun events(out: java.io.OutputStream, sendBody: Boolean) {
        val json = clips?.eventsJson(System.currentTimeMillis())
            ?: "{\"detection\":false,\"now\":${System.currentTimeMillis()},\"motion\":false,\"recording\":false,\"lastMotion\":null,\"clips\":[]}"
        HttpResponse.write(out, 200, "OK", "application/json", json.toByteArray(), sendBody)
    }

    /** `/clips/<id>.jpg` (thumbnail), `/clips/<id>.mjpeg` (download), `/clips/<id>/play` (replay at the original pace). */
    private fun clipRoute(client: Socket, out: java.io.OutputStream, path: String, head: Boolean) {
        val match = CLIP_PATH.matchEntire(path)
        val source = clips
        if (match == null || source == null) {
            HttpResponse.writeText(out, 404, "Not Found", !head)
            return
        }
        val (id, kind) = match.destructured
        val file = if (kind == ".jpg") source.thumbFile(id) else source.clipFile(id)
        if (file == null) {
            HttpResponse.writeText(out, 404, "Not Found", !head)
            return
        }
        when (kind) {
            ".jpg" -> HttpResponse.write(out, 200, "OK", "image/jpeg", file.readBytes(), !head)
            ".mjpeg" -> download(out, file, id, head)
            else -> replay(client, out, file, head)
        }
    }

    private fun download(out: java.io.OutputStream, file: java.io.File, id: String, head: Boolean) {
        HttpResponse.writeHeaders(
            out, 200, "OK", "video/x-motion-jpeg", file.length().toInt(),
            listOf("Content-Disposition: attachment; filename=\"camcast-$id.mjpeg\""),
        )
        if (!head) file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun replay(client: Socket, out: java.io.OutputStream, file: java.io.File, head: Boolean) {
        HttpResponse.writeHeaders(out, 200, "OK", MjpegWriter.CONTENT_TYPE)
        out.flush()
        if (head) return
        client.soTimeout = 0
        try {
            ClipReader(file).use { reader ->
                var previous: Long? = null
                while (running && !Thread.currentThread().isInterrupted) {
                    val frame = reader.next() ?: break
                    previous?.let { Thread.sleep((frame.timestampMillis - it).coerceIn(0, MAX_REPLAY_GAP_MILLIS)) }
                    previous = frame.timestampMillis
                    writingSince[client] = System.nanoTime()
                    MjpegWriter.writeFrame(out, frame.jpeg, frame.timestampMillis)
                    writingSince -= client
                }
            }
        } catch (e: InterruptedException) {
            // stop(): just end the replay
        } finally {
            writingSince -= client
        }
    }

    private fun statusPage(out: java.io.OutputStream, sendBody: Boolean) {
        val frame = frames.latest()
        val state = if (frame == null) "waiting for the first frame" else "${frame.width}x${frame.height}, frame ${frame.sequence}"
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>CamCast</title><h1>CamCast</h1><p>$state, $streamers viewer(s)</p>" +
            "<p><a href=/snapshot.jpg>snapshot.jpg</a> · <a href=/video>video</a></p><img src=/video style=\"max-width:100%\">"
        HttpResponse.write(out, 200, "OK", "text/html; charset=utf-8", html.toByteArray(), sendBody)
    }

    private fun snapshot(out: java.io.OutputStream, sendBody: Boolean) {
        val frame = frames.latest() ?: frames.awaitNewer(0, snapshotWaitMillis)
        if (frame == null) {
            HttpResponse.writeText(out, 503, "Camera not ready", sendBody, listOf("Retry-After: 2"))
            return
        }
        HttpResponse.write(out, 200, "OK", "image/jpeg", frame.jpeg, sendBody)
    }

    private fun video(client: Socket, out: java.io.OutputStream, head: Boolean) {
        HttpResponse.writeHeaders(out, 200, "OK", MjpegWriter.CONTENT_TYPE)
        out.flush()
        if (head) return
        // The request is fully read; from here on the client sends nothing, so drop the read timeout.
        client.soTimeout = 0
        streamingClients.incrementAndGet()
        try {
            var last = 0L
            while (running && !frames.isClosed && !Thread.currentThread().isInterrupted) {
                val frame = frames.awaitNewer(last, STREAM_POLL_MILLIS) ?: continue
                last = frame.sequence
                writingSince[client] = System.nanoTime()
                MjpegWriter.writeFrame(out, frame.jpeg, frame.timestampMillis)
                writingSince -= client
            }
        } finally {
            writingSince -= client
            streamingClients.decrementAndGet()
        }
    }

    private companion object {
        const val STREAM_POLL_MILLIS = 1_000L
        const val MAX_REPLAY_GAP_MILLIS = 1_000L
        val CLIP_PATH = Regex("/clips/([0-9]{8}-[0-9]{6}(?:-[0-9]{1,3})?)(\\.jpg|\\.mjpeg|/play)")
    }
}
