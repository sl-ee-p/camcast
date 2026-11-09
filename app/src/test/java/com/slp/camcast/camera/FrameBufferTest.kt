package com.slp.camcast.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBufferTest {
    private fun FrameBuffer.put(byte: Int) = publish(byteArrayOf(byte.toByte()), 2, 1, 0L)

    @Test
    fun startsEmptyAndNumbersFramesFromOne() {
        val b = FrameBuffer()
        assertNull(b.latest())
        assertEquals(1L, b.put(1).sequence)
        assertEquals(2L, b.put(2).sequence)
        assertEquals(2L, b.latest()!!.sequence)
    }

    @Test
    fun awaitReturnsAnAlreadyNewerFrameAtOnce() {
        val b = FrameBuffer()
        val f = b.put(1)
        assertSame(f, b.awaitNewer(0, 1_000))
    }

    @Test
    fun awaitTimesOutWhenNothingNewArrives() {
        val b = FrameBuffer()
        val f = b.put(1)
        assertNull(b.awaitNewer(f.sequence, 30))
    }

    @Test
    fun awaitWakesWhenAFrameIsPublished() {
        val b = FrameBuffer()
        val got = AtomicReference<JpegFrame?>()
        val done = CountDownLatch(1)
        Thread {
            got.set(b.awaitNewer(0, 5_000))
            done.countDown()
        }.start()
        Thread.sleep(50)
        b.put(7)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(7.toByte(), got.get()!!.jpeg[0])
    }

    @Test
    fun slowReaderSkipsStraightToTheLatestFrame() {
        val b = FrameBuffer()
        b.put(1); b.put(2); b.put(3)
        assertEquals(3L, b.awaitNewer(1, 100)!!.sequence)
    }

    @Test
    fun closeReleasesWaitersAndLaterWaits() {
        val b = FrameBuffer()
        val done = CountDownLatch(1)
        Thread {
            b.awaitNewer(0, 10_000)
            done.countDown()
        }.start()
        Thread.sleep(50)
        b.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(b.awaitNewer(0, 10_000))
    }
}
