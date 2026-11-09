package com.slp.camcast.server

import com.slp.camcast.camera.FrameBuffer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real server on a loopback socket. */
class CamServerTest {
    private val frames = FrameBuffer()
    private val creds = Credentials("cam", "pw")
    private val auth = "Authorization: Basic " + Base64.getEncoder().encodeToString("cam:pw".toByteArray())
    private var server: CamServer? = null
    private val sockets = mutableListOf<Socket>()
    private val jpeg = ByteArray(300) { (it * 7).toByte() }

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        server?.stop()
        frames.close()
    }

    private fun start(maxClients: Int = 6, snapshotWait: Long = 200): Int {
        val s = CamServer(0, frames, creds, maxClients, snapshotWait, requestTimeoutMillis = 2_000)
        server = s
        return s.start()
    }

    private fun connect(port: Int) = Socket(InetAddress.getLoopbackAddress(), port).also {
        it.soTimeout = 5_000
        sockets += it
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        fun line(): String {
            val sb = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) sb.write(b)
            }
            return sb.toString(Charsets.ISO_8859_1)
        }
        val status = line().split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
        }
        return status to headers
    }

    /** Sends a raw request and reads one whole response (the server closes after each). */
    private fun request(port: Int, raw: String): Reply {
        val s = connect(port)
        s.getOutputStream().write(raw.toByteArray())
        s.getOutputStream().flush()
        val (status, headers) = readHead(s.getInputStream())
        return Reply(status, headers, s.getInputStream().readBytes())
    }

    private fun get(port: Int, path: String, withAuth: Boolean = true, method: String = "GET") =
        request(port, "$method $path HTTP/1.1\r\nHost: x\r\n${if (withAuth) "$auth\r\n" else ""}\r\n")

    /** Reads the next MJPEG part from a /video stream. */
    private fun readPart(input: InputStream): ByteArray {
        val boundary = StringBuilder()
        while (!boundary.endsWith("--camcastframe\r\n")) {
            val b = input.read()
            assertTrue("stream ended", b != -1)
            boundary.append(b.toChar())
        }
        val (_, headers) = partHeaders(input)
        val len = headers.getValue("content-length").toInt()
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            assertTrue(n > 0)
            read += n
        }
        return body
    }

    private fun partHeaders(input: InputStream): Pair<Int, Map<String, String>> {
        val headers = HashMap<String, String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            assertTrue(b != -1)
            if (b == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                line.clear()
                if (l.isEmpty()) break
                headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
            } else {
                line.append(b.toChar())
            }
        }
        return 0 to headers
    }

    private fun openVideo(port: Int): InputStream {
        val s = connect(port)
        s.getOutputStream().write("GET /video HTTP/1.1\r\n$auth\r\n\r\n".toByteArray())
        val (status, headers) = readHead(s.getInputStream())
        assertEquals(200, status)
        assertEquals(MjpegWriter.CONTENT_TYPE, headers["content-type"])
        return s.getInputStream()
    }

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(cond())
    }

    @Test
    fun requestsWithoutOrWithWrongAuthGet401AndAChallenge() {
        val port = start()
        val none = get(port, "/snapshot.jpg", withAuth = false)
        assertEquals(401, none.status)
        assertTrue(none.headers.getValue("www-authenticate").startsWith("Basic realm=\"CamCast\""))
        val wrong = request(port, "GET / HTTP/1.1\r\nAuthorization: Basic " + Base64.getEncoder().encodeToString("cam:no".toByteArray()) + "\r\n\r\n")
        assertEquals(401, wrong.status)
    }

    @Test
    fun unauthenticatedClientsCannotProbePaths() {
        val port = start()
        assertEquals(401, get(port, "/does-not-exist", withAuth = false).status)
        assertEquals(404, get(port, "/does-not-exist").status)
    }

    @Test
    fun snapshotReturnsTheLatestFrame() {
        val port = start()
        frames.publish(byteArrayOf(1), 1, 1, 0)
        frames.publish(jpeg, 640, 480, 0)
        val r = get(port, "/snapshot.jpg?cachebust=1")
        assertEquals(200, r.status)
        assertEquals("image/jpeg", r.headers["content-type"])
        assertEquals(jpeg.size.toString(), r.headers["content-length"])
        assertArrayEquals(jpeg, r.body)
    }

    @Test
    fun snapshotWaitsBrieflyForTheFirstFrame() {
        val port = start(snapshotWait = 2_000)
        Thread { Thread.sleep(100); frames.publish(jpeg, 1, 1, 0) }.start()
        assertArrayEquals(jpeg, get(port, "/snapshot.jpg").body)
    }

    @Test
    fun snapshotIs503WhenThereIsNoFrame() {
        val port = start(snapshotWait = 50)
        val r = get(port, "/snapshot.jpg")
        assertEquals(503, r.status)
        assertEquals("2", r.headers["retry-after"])
    }

    @Test
    fun statusPageListsTheEndpoints() {
        val port = start()
        frames.publish(jpeg, 640, 480, 0)
        val r = get(port, "/")
        assertEquals(200, r.status)
        val html = String(r.body)
        assertTrue(html.contains("640x480"))
        assertTrue(html.contains("/snapshot.jpg") && html.contains("/video"))
    }

    @Test
    fun headReturnsHeadersWithoutABody() {
        val port = start()
        frames.publish(jpeg, 1, 1, 0)
        val r = get(port, "/snapshot.jpg", method = "HEAD")
        assertEquals(200, r.status)
        assertEquals(jpeg.size.toString(), r.headers["content-length"])
        assertEquals(0, r.body.size)
    }

    @Test
    fun badRequestsAreAnsweredNotDropped() {
        val port = start()
        assertEquals(405, request(port, "POST /video HTTP/1.1\r\n$auth\r\n\r\n").status)
        assertEquals(400, request(port, "nonsense\r\n\r\n").status)
    }

    @Test
    fun videoStreamsFramesInOrder() {
        val port = start()
        frames.publish(byteArrayOf(0), 1, 1, 0) // an older frame is already there
        val input = openVideo(port)
        assertEquals(1, readPart(input).size) // the current frame comes first
        val a = ByteArray(500) { 1 }
        val b = ByteArray(700) { 2 }
        frames.publish(a, 1, 1, 0)
        assertArrayEquals(a, readPart(input))
        frames.publish(b, 1, 1, 0)
        assertArrayEquals(b, readPart(input))
    }

    @Test
    fun severalClientsAllReceiveFrames() {
        val port = start()
        val inputs = (1..3).map { openVideo(port) }
        waitUntil { server!!.streamers == 3 }
        frames.publish(jpeg, 1, 1, 0)
        inputs.forEach { assertArrayEquals(jpeg, readPart(it)) }
    }

    @Test
    fun clientsBeyondTheLimitGet503AndSlotsFreeUp() {
        val port = start(maxClients = 1)
        frames.publish(jpeg, 1, 1, 0)
        val first = connect(port)
        first.getOutputStream().write("GET /video HTTP/1.1\r\n$auth\r\n\r\n".toByteArray())
        readHead(first.getInputStream())
        waitUntil { server!!.streamers == 1 }
        assertEquals(503, get(port, "/snapshot.jpg").status)
        first.close()
        // The server notices the dead client at the next write.
        waitUntil {
            frames.publish(jpeg, 1, 1, 0)
            get(port, "/snapshot.jpg").status == 200
        }
    }

    @Test
    fun stopEndsStreamsAndReleasesThePort() {
        val port = start()
        frames.publish(jpeg, 1, 1, 0)
        val input = openVideo(port)
        readPart(input)
        server!!.stop()
        // The stream ends instead of hanging.
        val ended = CountDownLatch(1)
        Thread { runCatching { input.readBytes() }; ended.countDown() }.start()
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertEquals(0, server!!.streamers)
        // The port can be bound again.
        val again = CamServer(port, frames, creds)
        assertEquals(port, again.start())
        again.stop()
    }

    @Test
    fun closingTheFrameBufferEndsStreams() {
        val port = start()
        frames.publish(jpeg, 1, 1, 0)
        val input = openVideo(port)
        readPart(input)
        frames.close()
        val ended = CountDownLatch(1)
        Thread { runCatching { input.readBytes() }; ended.countDown() }.start()
        assertTrue(ended.await(4, TimeUnit.SECONDS))
    }

    @Test
    fun idleConnectionsAreDroppedAfterTheTimeout() {
        val port = start()
        val s = connect(port)
        s.soTimeout = 6_000
        assertEquals(-1, s.getInputStream().read()) // server closes after its 2 s request timeout
    }

    @Test
    fun refusesToStartWithoutUsableCredentials() {
        val s = CamServer(0, frames, Credentials("cam", ""))
        val failed = runCatching { s.start() }.exceptionOrNull()
        assertNotNull(failed)
        assertNull(server)
        assertFalse(s.streamers > 0)
    }
}
