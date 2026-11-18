package com.slp.camcast.clips

import com.slp.camcast.camera.JpegFrame
import java.io.File
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipRecorderTest {
    private lateinit var dir: File
    private lateinit var store: ClipStore
    private val direct = Executor { it.run() }
    private val t0 = 1_763_490_000_000L // some afternoon in November 2025
    private var seq = 0L

    @Before
    fun setUp() {
        dir = File.createTempFile("clips", "").apply { delete(); mkdirs() }
        store = ClipStore(dir, freeBytes = { Long.MAX_VALUE })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A recognisable fake JPEG for the frame at [millis] after t0. */
    private fun frame(millis: Long, size: Int = 100) =
        JpegFrame(ByteArray(size) { (millis / 100 + it).toByte() }, 640, 480, t0 + millis, ++seq)

    /** Feeds 10 fps frames from [fromMs] until [toMs] (exclusive); motion where [motionAt] says so. */
    private fun feed(r: ClipRecorder, fromMs: Long, toMs: Long, size: Int = 100, motionAt: (Long) -> Boolean = { false }) {
        var t = fromMs
        while (t < toMs) {
            r.onFrame(frame(t, size), motionAt(t))
            t += 100
        }
    }

    private fun frames(id: String): List<ClipFrame> = ClipReader(store.clipFile(id)).use { r ->
        generateSequence { r.next() }.toList()
    }

    @Test
    fun aClipHolds30sBeforeAnd30sAfterTheMovement() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 120_000) { it == 60_000L }
        val clips = store.list()
        assertEquals(1, clips.size)
        val c = clips[0]
        assertEquals(t0 + 60_000, c.motionMillis)
        assertEquals(t0 + 30_000, c.startMillis)
        assertEquals(t0 + 90_000, c.endMillis)
        val f = frames(c.id)
        assertEquals(601, f.size)
        assertEquals(c.frames, f.size)
        assertEquals(t0 + 30_000, f.first().timestampMillis)
        assertEquals(t0 + 90_000, f.last().timestampMillis)
        assertArrayEquals(frame(60_000).jpeg, store.thumbFile(c.id).readBytes()) // the movement frame
        assertEquals(store.clipFile(c.id).length(), c.bytes)
        assertFalse(r.isRecording)
        assertEquals(t0 + 60_000, r.lastMotionMillis)
    }

    @Test
    fun recordingSinceIsTheMovementBehindTheCurrentClip() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 50_000) { it == 45_000L || it == 48_000L }
        assertEquals(t0 + 45_000, r.recordingSinceMillis)
        feed(r, 50_000, 90_000)
        assertNull(r.recordingSinceMillis)
    }

    @Test
    fun moreMovementExtendsTheClip() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 150_000) { it == 40_000L || it == 65_000L }
        val c = store.list().single()
        assertEquals(t0 + 10_000, c.startMillis)
        assertEquals(t0 + 95_000, c.endMillis)
    }

    @Test
    fun movementRightAtTheStartHasAShortPreRoll() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 60_000) { it == 5_000L }
        assertEquals(t0, store.list().single().startMillis)
    }

    @Test
    fun endlessMovementIsSplitIntoClipsThatDoNotOverlap() {
        val r = ClipRecorder(store, direct, maxClipMillis = 60_000)
        feed(r, 0, 200_000) { it >= 40_000L && it < 150_000L }
        val clips = store.list().sortedBy { it.startMillis }
        assertEquals(3, clips.size) // 10-70 s, 70.1-130.1 s, 130.2-179.9 s
        assertEquals(t0 + 10_000, clips[0].startMillis)
        clips.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis + 100, b.startMillis) }
        assertEquals(t0 + 179_900, clips[2].endMillis) // last motion 149.9 s + 30 s
    }

    @Test
    fun thePreRollIsCappedInMemory() {
        val r = ClipRecorder(store, direct, maxBufferBytes = 50_000)
        feed(r, 0, 80_000, size = 1_000) { it == 40_000L }
        val c = store.list().single()
        assertTrue("pre-roll ${(c.motionMillis - c.startMillis)} ms", c.motionMillis - c.startMillis <= 5_000)
    }

    @Test
    fun stoppingMidClipKeepsWhatWasRecorded() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 50_000) { it == 45_000L }
        assertTrue(r.isRecording)
        assertTrue(store.list().isEmpty())
        r.close()
        val c = store.list().single()
        assertEquals(t0 + 49_900, c.endMillis)
        assertFalse(store.partFile(c.id).exists())
    }

    @Test
    fun aCrashLeftoverBecomesAClipEndingAtItsLastWholeFrame() {
        val r = ClipRecorder(store, direct)
        feed(r, 0, 50_000) { it == 45_000L } // recording, never closed: like a kill
        val id = dir.listFiles()!!.single { it.name.endsWith(".part") }.name.removeSuffix(".mjpeg.part")
        val part = store.partFile(id)
        part.writeBytes(part.readBytes().copyOf(part.length().toInt() - 50)) // cut into the last frame
        ClipStore(dir, freeBytes = { Long.MAX_VALUE }).recoverPartials()
        val c = store.list().single()
        assertEquals(id, c.id)
        assertEquals(frames(id).size, c.frames)
        assertEquals(t0 + 49_800, c.endMillis)
    }

    @Test
    fun oldClipsGoWhenTheFolderIsFull() {
        val small = ClipStore(dir, maxBytes = 250_000, freeBytes = { Long.MAX_VALUE })
        val r = ClipRecorder(small, direct)
        // Three separate movements, each clip ~100 KB (601 frames of 100 bytes + part headers).
        feed(r, 0, 400_000, size = 100) { it == 40_000L || it == 160_000L || it == 280_000L }
        val left = small.list()
        assertEquals(2, left.size)
        assertEquals(t0 + 280_000, left[0].motionMillis)
        assertEquals(t0 + 160_000, left[1].motionMillis)
    }

    @Test
    fun lowDiskSpaceAlsoDeletesTheOldest() {
        var free = Long.MAX_VALUE
        val s = ClipStore(dir, minFreeBytes = 1_000, freeBytes = { free })
        val r = ClipRecorder(s, direct)
        feed(r, 0, 100_000) { it == 40_000L }
        free = 10
        feed(r, 100_000, 250_000) { it == 160_000L }
        assertTrue(s.list().isEmpty()) // still short of space after deleting both: nothing left to delete
    }

    @Test
    fun idsAreTimestampsAndNeverReused() {
        val id = store.newId(t0)
        assertTrue(ClipInfo.ID.matches(id))
        store.partFile(id).writeText("x")
        assertEquals("$id-2", store.newId(t0))
    }

    @Test
    fun onlyRealIdsAreLookedUp() {
        assertNull(store.find("../../etc/passwd"))
        assertNull(store.find("20261118-190703.json"))
        val r = ClipRecorder(store, direct)
        feed(r, 0, 80_000) { it == 40_000L }
        val id = store.list().single().id
        assertNotNull(store.find(id))
    }

    @Test
    fun infoSurvivesAJsonRoundTrip() {
        val info = ClipInfo("20261118-190703-2", 1, 2, 3, 4, 5, 1080, 1920)
        assertEquals(info, ClipInfo.fromJson(info.toJson()))
        assertNull(ClipInfo.fromJson("{\"id\":\"../x\",\"start\":1,\"motion\":2,\"end\":3,\"frames\":4,\"bytes\":5}"))
    }
}
