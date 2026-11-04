package com.phonemonitor.app.uvc

/** Pixel formats the app can display. [nativeId] matches FMT_* in uvc_stream.c. */
enum class PixelFormat(val nativeId: Int, val label: String) {
    MJPEG(1, "MJPEG"),
    YUY2(2, "YUY2"),
    NV12(3, "NV12"),
}

data class UvcFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    /** Frame intervals in 100 ns units, shortest (fastest) first. */
    val intervals: List<Long>,
    val defaultInterval: Long,
    val maxFrameBufferSize: Int,
    /** True when the device accepts any interval between intervals.first() and intervals.last(). */
    val continuous: Boolean = false,
) {
    val maxFps: Double get() = intervals.firstOrNull()?.let { 1e7 / it } ?: 0.0
}

data class UvcFormat(
    val index: Int,
    val pixelFormat: PixelFormat,
    val frames: List<UvcFrame>,
)

/** One alternate setting of the streaming interface that carries a video IN endpoint. */
data class UvcEndpointAlt(
    val altSetting: Int,
    val endpointAddress: Int,
    val isIso: Boolean,
    /** Bytes the endpoint can move per (micro)frame, incl. high-bandwidth / SuperSpeed burst. */
    val bytesPerInterval: Int,
)

data class UvcStreamingInterface(
    val interfaceNumber: Int,
    val formats: List<UvcFormat>,
    val alts: List<UvcEndpointAlt>,
)

data class UvcDeviceInfo(
    /** bcdUVC, e.g. 0x0100, 0x0110, 0x0150. */
    val uvcVersion: Int,
    val controlInterface: Int,
    val streams: List<UvcStreamingInterface>,
    /** Camera Terminal entity ID and its bmControls bitmap (UVC 1.5 table 3-6), -1/0 if absent. */
    val cameraTerminalId: Int = -1,
    val cameraControls: Long = 0,
    /** Processing Unit entity ID and its bmControls bitmap (UVC 1.5 table 3-8), -1/0 if absent. */
    val processingUnitId: Int = -1,
    val processingControls: Long = 0,
)

/**
 * Parses the raw USB descriptors (UsbDeviceConnection.getRawDescriptors()) of a
 * UVC device. Pure Kotlin so it can be unit tested on the JVM.
 */
object UvcDescriptorParser {
    private const val DT_CONFIG = 0x02
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_CS_INTERFACE = 0x24
    private const val DT_SS_EP_COMPANION = 0x30

    private const val CC_VIDEO = 0x0E
    private const val SC_VIDEOCONTROL = 0x01
    private const val SC_VIDEOSTREAMING = 0x02

    private const val VC_HEADER = 0x01
    private const val VC_INPUT_TERMINAL = 0x02
    private const val VC_PROCESSING_UNIT = 0x05
    private const val ITT_CAMERA = 0x0201
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07

    /** MPEG2-TS, DV, frame-based (H.264/HEVC), stream-based, H.264, VP8 format descriptors. */
    private val UNSUPPORTED_FORMATS = setOf(0x0A, 0x0C, 0x10, 0x12, 0x13, 0x15, 0x16, 0x18)

    private class FormatBuilder(val index: Int, val pixelFormat: PixelFormat?) {
        val frames = mutableListOf<UvcFrame>()
    }

    private class StreamBuilder(val interfaceNumber: Int) {
        val formats = mutableListOf<FormatBuilder>()
        val alts = mutableListOf<UvcEndpointAlt>()
    }

    fun parse(raw: ByteArray): UvcDeviceInfo? {
        var uvcVersion = 0x0100
        var cameraTerminalId = -1
        var cameraControls = 0L
        var processingUnitId = -1
        var processingControls = 0L
        var controlInterface = -1
        val streams = linkedMapOf<Int, StreamBuilder>()

        var configsSeen = 0
        var curClass = -1
        var curSubClass = -1
        var curInterface = -1
        var curAlt = -1
        var currentFormat: FormatBuilder? = null
        // Index into the current stream's alts list of the last parsed endpoint, for the SS companion.
        var lastEndpointPos = -1
        var lastEndpointMaxPacket = 0

        var i = 0
        while (i + 2 <= raw.size) {
            val len = u8(raw, i)
            if (len < 2 || i + len > raw.size) break
            val type = u8(raw, i + 1)
            when (type) {
                DT_CONFIG -> {
                    configsSeen++
                    if (configsSeen > 1) break // only the first configuration
                }
                DT_INTERFACE -> if (len >= 9) {
                    curInterface = u8(raw, i + 2)
                    curAlt = u8(raw, i + 3)
                    curClass = u8(raw, i + 5)
                    curSubClass = u8(raw, i + 6)
                    lastEndpointPos = -1
                    if (curClass == CC_VIDEO && curSubClass == SC_VIDEOCONTROL && controlInterface < 0) {
                        controlInterface = curInterface
                    }
                    if (curClass == CC_VIDEO && curSubClass == SC_VIDEOSTREAMING) {
                        streams.getOrPut(curInterface) { StreamBuilder(curInterface) }
                    }
                    currentFormat = null
                }
                DT_CS_INTERFACE -> if (curClass == CC_VIDEO && len >= 3) {
                    val subtype = u8(raw, i + 2)
                    if (curSubClass == SC_VIDEOCONTROL && subtype == VC_HEADER && len >= 5) {
                        uvcVersion = u16(raw, i + 3)
                    } else if (curSubClass == SC_VIDEOCONTROL && subtype == VC_INPUT_TERMINAL && len >= 15 &&
                        u16(raw, i + 4) == ITT_CAMERA && cameraTerminalId < 0
                    ) {
                        cameraTerminalId = u8(raw, i + 3)
                        cameraControls = bitmap(raw, i + 15, minOf(u8(raw, i + 14), len - 15))
                    } else if (curSubClass == SC_VIDEOCONTROL && subtype == VC_PROCESSING_UNIT && len >= 8 &&
                        processingUnitId < 0
                    ) {
                        processingUnitId = u8(raw, i + 3)
                        processingControls = bitmap(raw, i + 8, minOf(u8(raw, i + 7), len - 8))
                    } else if (curSubClass == SC_VIDEOSTREAMING) {
                        val stream = streams[curInterface]
                        when (subtype) {
                            VS_FORMAT_MJPEG -> if (len >= 11 && stream != null) {
                                currentFormat = FormatBuilder(u8(raw, i + 3), PixelFormat.MJPEG)
                                    .also { stream.formats += it }
                            }
                            VS_FORMAT_UNCOMPRESSED -> if (len >= 27 && stream != null) {
                                val fourcc = String(raw, i + 5, 4, Charsets.US_ASCII)
                                val pf = when (fourcc) {
                                    "YUY2" -> PixelFormat.YUY2
                                    "NV12" -> PixelFormat.NV12
                                    else -> null
                                }
                                currentFormat = FormatBuilder(u8(raw, i + 3), pf)
                                    .also { stream.formats += it }
                            }
                            VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED -> if (len >= 26) {
                                val fmt = currentFormat
                                val isMjpegFormat = fmt?.pixelFormat == PixelFormat.MJPEG
                                if (fmt != null && isMjpegFormat == (subtype == VS_FRAME_MJPEG)) {
                                    parseFrame(raw, i, len)?.let { fmt.frames += it }
                                }
                            }
                            in UNSUPPORTED_FORMATS -> currentFormat = null
                        }
                    }
                }
                DT_ENDPOINT -> if (len >= 7 && curClass == CC_VIDEO && curSubClass == SC_VIDEOSTREAMING) {
                    val addr = u8(raw, i + 2)
                    val attrs = u8(raw, i + 3)
                    val mps = u16(raw, i + 4)
                    val xfer = attrs and 0x03
                    val isIn = addr and 0x80 != 0
                    if (isIn && (xfer == 1 || xfer == 2)) {
                        val stream = streams[curInterface]
                        if (stream != null) {
                            val base = mps and 0x7FF
                            val mult = ((mps shr 11) and 0x3) + 1
                            stream.alts += UvcEndpointAlt(curAlt, addr, xfer == 1, base * mult)
                            lastEndpointPos = stream.alts.size - 1
                            lastEndpointMaxPacket = base
                        }
                    } else {
                        lastEndpointPos = -1
                    }
                }
                DT_SS_EP_COMPANION -> if (len >= 6 && lastEndpointPos >= 0) {
                    val stream = streams[curInterface]
                    if (stream != null) {
                        val ep = stream.alts[lastEndpointPos]
                        if (ep.isIso) {
                            val maxBurst = u8(raw, i + 2)
                            val ssMult = (u8(raw, i + 3) and 0x3)
                            var bpi = u16(raw, i + 4)
                            if (bpi == 0) bpi = lastEndpointMaxPacket * (maxBurst + 1) * (ssMult + 1)
                            stream.alts[lastEndpointPos] = ep.copy(bytesPerInterval = bpi)
                        }
                    }
                }
            }
            i += len
        }

        if (controlInterface < 0 && streams.isEmpty()) return null
        val result = streams.values.map { sb ->
            UvcStreamingInterface(
                interfaceNumber = sb.interfaceNumber,
                formats = sb.formats
                    .filter { it.pixelFormat != null && it.frames.isNotEmpty() }
                    .map { UvcFormat(it.index, it.pixelFormat!!, it.frames.toList()) },
                alts = sb.alts.toList(),
            )
        }
        return UvcDeviceInfo(
            uvcVersion, controlInterface, result,
            cameraTerminalId, cameraControls, processingUnitId, processingControls,
        )
    }

    private fun parseFrame(raw: ByteArray, i: Int, len: Int): UvcFrame? {
        val index = u8(raw, i + 3)
        val width = u16(raw, i + 5)
        val height = u16(raw, i + 7)
        val maxBuf = u32(raw, i + 17).toInt()
        val defInterval = u32(raw, i + 21)
        val intervalType = u8(raw, i + 25)
        val intervals = mutableListOf<Long>()
        if (intervalType == 0) {
            if (len >= 38) {
                intervals += u32(raw, i + 26) // min (fastest)
                intervals += u32(raw, i + 30) // max
            }
        } else {
            for (k in 0 until intervalType) {
                val off = i + 26 + 4 * k
                if (off + 4 > i + len) break
                intervals += u32(raw, off)
            }
        }
        if (intervals.isEmpty() && defInterval > 0) intervals += defInterval
        val cleaned = intervals.filter { it > 0 }.distinct().sorted()
        if (width <= 0 || height <= 0) return null
        return UvcFrame(index, width, height, cleaned, defInterval, maxBuf, continuous = intervalType == 0 && cleaned.size >= 2)
    }

    private fun bitmap(b: ByteArray, i: Int, size: Int): Long {
        var v = 0L
        for (k in 0 until minOf(size, 8)) v = v or (u8(b, i + k).toLong() shl (8 * k))
        return v
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
