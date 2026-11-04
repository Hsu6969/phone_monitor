package com.phonemonitor.app.uvc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class UvcDescriptorParserTest {

    private class Desc {
        val out = ByteArrayOutputStream()
        fun add(type: Int, vararg body: Int) {
            out.write(body.size + 2)
            out.write(type)
            body.forEach { out.write(it and 0xFF) }
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun le16(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun le32(v: Long) = IntArray(4) { ((v shr (8 * it)) and 0xFF).toInt() }

    private fun frame(subtype: Int, index: Int, w: Int, h: Int, intervals: List<Long>, continuous: Boolean = false): IntArray {
        val head = intArrayOf(subtype, index, 0) + le16(w) + le16(h) +
            le32(1000) + le32(100000) + le32(w.toLong() * h * 2) + le32(intervals.first())
        return if (continuous) {
            head + intArrayOf(0) + le32(intervals[0]) + le32(intervals[1]) + le32(1)
        } else {
            head + intArrayOf(intervals.size) + intervals.flatMap { le32(it).toList() }
        }
    }

    private fun sampleDevice(): ByteArray {
        val d = Desc()
        d.add(0x01, *IntArray(16)) // device descriptor (contents irrelevant)
        d.add(0x02, *IntArray(7))  // configuration 1
        d.add(0x0B, 0, 2, 0x0E, 0x03, 0, 0) // IAD
        d.add(0x04, 0, 0, 1, 0x0E, 0x01, 0, 0) // VC interface 0
        d.add(0x24, 0x01, *le16(0x0110), *le16(0x30), *le32(48_000_000), 1, 1) // VC header, UVC 1.1
        // Camera terminal (ID 1): exposure abs (bit 3), iris abs (bit 7), zoom abs (bit 9)
        d.add(0x24, 0x02, 1, *le16(0x0201), 0, 0, *le16(0), *le16(0), *le16(0), 3, 0x88, 0x02, 0x00)
        // Processing unit (ID 2): gain (bit 9)
        d.add(0x24, 0x05, 2, 1, *le16(0), 2, 0x00, 0x02, 0)
        d.add(0x05, 0x83, 0x03, *le16(16), 8) // VC interrupt endpoint (ignored)
        d.add(0x04, 1, 0, 0, 0x0E, 0x02, 0, 0) // VS interface 1 alt 0
        d.add(0x24, 0x01, 2, *le16(0), 0x81, 0, 1, 0, 0, 0, 1, 0, 0) // VS input header
        // MJPEG format with 2 frames
        d.add(0x24, 0x06, 1, 2, 1, 1, 0, 0, 0, 0)
        d.add(0x24, *frame(0x07, 1, 1920, 1080, listOf(333_333, 666_666)))
        d.add(0x24, *frame(0x07, 2, 1280, 720, listOf(166_666, 333_333), continuous = true))
        // Uncompressed YUY2 format with 1 frame
        val yuy2Guid = intArrayOf('Y'.code, 'U'.code, 'Y'.code, '2'.code, 0x00, 0x00, 0x10, 0x00,
            0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71)
        d.add(0x24, 0x04, 2, 1, *yuy2Guid, 16, 1, 0, 0, 0, 0)
        d.add(0x24, *frame(0x05, 1, 640, 480, listOf(333_333)))
        // H.264 frame-based format: must be ignored
        d.add(0x24, 0x10, 3, 1, *IntArray(16), 16, 1, 0, 0, 0, 0, 1)
        // alt 1: 512 B iso, alt 2: 3 x 1024 B high-bandwidth iso
        d.add(0x04, 1, 1, 1, 0x0E, 0x02, 0, 0)
        d.add(0x05, 0x81, 0x05, *le16(0x0200), 1)
        d.add(0x04, 1, 2, 1, 0x0E, 0x02, 0, 0)
        d.add(0x05, 0x81, 0x05, *le16(0x1400), 1)
        // A second configuration that must be ignored.
        d.add(0x02, *IntArray(7))
        d.add(0x04, 5, 0, 0, 0x0E, 0x02, 0, 0)
        return d.bytes()
    }

    @Test
    fun parsesFormatsFramesAndAlts() {
        val info = UvcDescriptorParser.parse(sampleDevice())
        assertNotNull(info)
        info!!
        assertEquals(0x0110, info.uvcVersion)
        assertEquals(0, info.controlInterface)
        assertEquals(1, info.cameraTerminalId)
        assertEquals((1L shl 3) or (1L shl 7) or (1L shl 9), info.cameraControls)
        assertEquals(2, info.processingUnitId)
        assertEquals(1L shl 9, info.processingControls)
        assertEquals(1, info.streams.size)

        val vs = info.streams[0]
        assertEquals(1, vs.interfaceNumber)
        assertEquals(listOf(PixelFormat.MJPEG, PixelFormat.YUY2), vs.formats.map { it.pixelFormat })

        val mjpeg = vs.formats[0]
        assertEquals(1, mjpeg.index)
        assertEquals(listOf(1920 to 1080, 1280 to 720), mjpeg.frames.map { it.width to it.height })
        assertEquals(listOf(333_333L, 666_666L), mjpeg.frames[0].intervals)
        assertEquals(listOf(166_666L, 333_333L), mjpeg.frames[1].intervals)
        assertEquals(60.0, mjpeg.frames[1].maxFps, 0.01)
        assertTrue(mjpeg.frames[1].continuous)
        assertTrue(!mjpeg.frames[0].continuous)

        val yuy2 = vs.formats[1]
        assertEquals(2, yuy2.index)
        assertEquals(640, yuy2.frames.single().width)

        assertEquals(
            listOf(UvcEndpointAlt(1, 0x81, true, 512), UvcEndpointAlt(2, 0x81, true, 3072)),
            vs.alts,
        )
    }

    @Test
    fun superSpeedCompanionSetsBytesPerInterval() {
        val d = Desc()
        d.add(0x02, *IntArray(7))
        d.add(0x04, 0, 0, 1, 0x0E, 0x01, 0, 0)
        d.add(0x24, 0x01, *le16(0x0100), *le16(0x0D), *le32(48_000_000), 1, 1)
        d.add(0x04, 1, 0, 0, 0x0E, 0x02, 0, 0)
        d.add(0x24, 0x06, 1, 1, 1, 1, 0, 0, 0, 0)
        d.add(0x24, *frame(0x07, 1, 3840, 2160, listOf(333_333)))
        d.add(0x04, 1, 1, 1, 0x0E, 0x02, 0, 0)
        d.add(0x05, 0x82, 0x05, *le16(1024), 1)
        d.add(0x30, 15, 1, *le16(0)) // bMaxBurst=15, Mult=1, wBytesPerInterval=0 -> computed
        val info = UvcDescriptorParser.parse(d.bytes())!!
        assertEquals(0x0100, info.uvcVersion)
        assertEquals(1024 * 16 * 2, info.streams[0].alts.single().bytesPerInterval)
    }

    @Test
    fun bulkDeviceHasNonIsoEndpoint() {
        val d = Desc()
        d.add(0x02, *IntArray(7))
        d.add(0x04, 0, 0, 0, 0x0E, 0x01, 0, 0)
        d.add(0x04, 1, 0, 1, 0x0E, 0x02, 0, 0)
        d.add(0x24, 0x06, 1, 1, 1, 1, 0, 0, 0, 0)
        d.add(0x24, *frame(0x07, 1, 1920, 1080, listOf(333_333)))
        d.add(0x05, 0x81, 0x02, *le16(512), 0)
        val alts = UvcDescriptorParser.parse(d.bytes())!!.streams[0].alts
        assertEquals(1, alts.size)
        assertTrue(!alts[0].isIso)
        assertEquals(0, alts[0].altSetting)
    }

    @Test
    fun nonVideoDeviceReturnsNull() {
        val d = Desc()
        d.add(0x02, *IntArray(7))
        d.add(0x04, 0, 0, 3, 0x06, 0x01, 0x01, 0) // PTP still-image interface
        assertEquals(null, UvcDescriptorParser.parse(d.bytes()))
    }
}
