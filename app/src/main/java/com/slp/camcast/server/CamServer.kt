package com.slp.camcast.server

import com.slp.camcast.camera.FrameBuffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val clients = Collections.synchronizedSet(HashSet<Socket>())
    private val activeClients = AtomicInteger()
    private val streamingClients = AtomicInteger()

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
            else -> HttpResponse.writeText(out, 404, "Not Found", !head)
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
                MjpegWriter.writeFrame(out, frame.jpeg, frame.timestampMillis)
            }
        } finally {
            streamingClients.decrementAndGet()
        }
    }

    private companion object {
        const val STREAM_POLL_MILLIS = 1_000L
    }
}
