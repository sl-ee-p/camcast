package com.slp.camcast.server

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MjpegWriterTest {
    @Test
    fun frameIsBoundaryHeadersBodyAndCrlf() {
        val out = ByteArrayOutputStream()
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        MjpegWriter.writeFrame(out, jpeg, 1234L)
        val expected = "--camcastframe\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\nX-Timestamp: 1234\r\n\r\n".toByteArray() +
            jpeg + "\r\n".toByteArray()
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun framesFollowEachOtherWithoutExtraBytes() {
        val out = ByteArrayOutputStream()
        MjpegWriter.writeFrame(out, byteArrayOf(9), 1)
        val one = out.size()
        MjpegWriter.writeFrame(out, byteArrayOf(9), 1)
        assertEquals(one * 2, out.size())
    }

    @Test
    fun contentTypeNamesTheBoundary() {
        assertEquals("multipart/x-mixed-replace; boundary=camcastframe", MjpegWriter.CONTENT_TYPE)
    }

    @Test
    fun binaryBodyIsWrittenUntouched() {
        val jpeg = ByteArray(256) { it.toByte() } // includes 0x0D 0x0A and 0xFF 0xD8-like bytes
        val out = ByteArrayOutputStream()
        MjpegWriter.writeFrame(out, jpeg, 0)
        val bytes = out.toByteArray()
        val bodyStart = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertArrayEquals(jpeg, bytes.copyOfRange(bodyStart, bodyStart + 256))
    }
}
