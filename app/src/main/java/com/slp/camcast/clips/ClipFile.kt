package com.slp.camcast.clips

import com.slp.camcast.server.MjpegWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * A clip on disk is the live stream's own MJPEG framing (`--camcastframe`, Content-Length, X-Timestamp per part), so
 * VLC and ffplay open it, and [ClipReader] can replay it with the original timing.
 */
class ClipFrame(val jpeg: ByteArray, val timestampMillis: Long)

class ClipReader(file: File) : AutoCloseable {
    private val input: InputStream = BufferedInputStream(file.inputStream(), 64 * 1024)

    /** The next frame, or null at the end (a clip cut short by a crash ends at its last whole frame). */
    fun next(): ClipFrame? {
        var length = -1
        var timestamp = 0L
        var line = readLine() ?: return null
        while (line.isEmpty()) line = readLine() ?: return null // the CRLF after the previous part
        if (line != "--${MjpegWriter.BOUNDARY}") return null
        while (true) {
            line = readLine() ?: return null
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            if (name == "content-length") length = value.toIntOrNull() ?: -1
            if (name == "x-timestamp") timestamp = value.toLongOrNull() ?: 0L
        }
        if (length !in 0..MAX_FRAME_BYTES) return null
        val jpeg = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(jpeg, read, length - read)
            if (n < 0) return null
            read += n
        }
        return ClipFrame(jpeg, timestamp)
    }

    private fun readLine(): String? {
        val sb = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.size() == 0) null else sb.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return sb.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (sb.size() > MAX_LINE) return null
            sb.write(b)
        }
    }

    override fun close() = input.close()

    private companion object {
        const val MAX_LINE = 1024
        const val MAX_FRAME_BYTES = 20_000_000
    }
}
