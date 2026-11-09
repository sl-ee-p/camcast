package com.slp.camcast.server

import java.io.OutputStream

/** multipart/x-mixed-replace framing, the format browsers, VLC, ffmpeg, go2rtc and Frigate read. */
object MjpegWriter {
    const val BOUNDARY = "camcastframe"
    const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"

    fun writeFrame(out: OutputStream, jpeg: ByteArray, timestampMillis: Long) {
        out.write(
            ("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n" +
                "X-Timestamp: $timestampMillis\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.write(jpeg)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}
