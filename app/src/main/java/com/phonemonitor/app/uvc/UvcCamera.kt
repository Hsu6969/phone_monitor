package com.phonemonitor.app.uvc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class UvcException(message: String, val errno: Int = 0) : Exception(message)

/** A selectable video mode: one format + frame size + frame interval. */
data class UvcMode(val format: UvcFormat, val frame: UvcFrame, val interval: Long) {
    val width get() = frame.width
    val height get() = frame.height
    val fps get() = if (interval > 0) 1e7 / interval else 0.0
    val key get() = "${format.pixelFormat.name}:${frame.width}x${frame.height}@$interval"
    val fpsLabel: String
        get() = if (kotlin.math.abs(fps - Math.round(fps)) < 0.05) "%.0f".format(fps) else "%.2f".format(fps)
    val label get() = "${frame.width}×${frame.height}  ${fpsLabel}fps  ${format.pixelFormat.label}"
}

/**
 * An opened UVC device. Open with [UvcCamera.open], then call [start] to get a
 * [StreamSession]. Not thread-safe: use it from one worker thread.
 */
class UvcCamera private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val info: UvcDeviceInfo,
    private val streamIf: UvcStreamingInterface,
) : Closeable {

    private val claimed = mutableListOf<UsbInterface>()
    private var session: StreamSession? = null

    val displayName: String
        get() = device.productName?.takeIf { it.isNotBlank() } ?: "USB 影像裝置"

    /** Every format × frame size × frame rate the device advertises. */
    val modes: List<UvcMode> by lazy {
        streamIf.formats.flatMap { fmt ->
            fmt.frames.flatMap { fr -> intervalsOf(fr).map { UvcMode(fmt, fr, it) } }
        }.sortedWith(
            compareBy<UvcMode> { it.format.pixelFormat != PixelFormat.MJPEG }
                .thenByDescending { it.width * it.height }
                .thenByDescending { it.fps }
        )
    }

    /** Prefers MJPEG 1920x1080 (what HDMI capture cards and cameras usually provide). */
    fun defaultMode(): UvcMode {
        val mjpeg = modes.filter { it.format.pixelFormat == PixelFormat.MJPEG }
        val pool = mjpeg.ifEmpty { modes.filter { it.fps >= 24 }.ifEmpty { modes } }
        val size = pool.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: pool.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
            ?: pool.first()
        val sameSize = pool.filter { it.format == size.format && it.frame == size.frame }
        // Fastest rate up to 60 fps, otherwise the slowest offered.
        return sameSize.filter { it.fps <= 60.5 }.maxByOrNull { it.fps }
            ?: sameSize.minByOrNull { it.fps }
            ?: size
    }

    private fun intervalsOf(fr: UvcFrame): List<Long> {
        if (!fr.continuous) return fr.intervals.ifEmpty { listOf(fr.defaultInterval) }
        val min = fr.intervals.first()
        val max = fr.intervals.last()
        val common = COMMON_FPS.map { Math.round(1e7 / it) }.filter { it in min..max }
        return (common + min + max).distinct().sorted()
    }

    fun start(mode: UvcMode): StreamSession {
        stop()
        setAlt(0)
        val probe = negotiate(mode)
        val maxFrameSize = u32(probe, 18)
        val maxPayload = u32(probe, 22)
        Log.i(TAG, "negotiated ${mode.label}: maxFrame=$maxFrameSize maxPayload=$maxPayload")

        val isoAlts = streamIf.alts.filter { it.isIso && it.altSetting > 0 }.sortedBy { it.bytesPerInterval }
        val fd = connection.fileDescriptor
        val err = IntArray(1)
        val handle: Long
        if (isoAlts.isNotEmpty()) {
            // Smallest alternate setting that can carry the negotiated payload, then larger ones.
            val firstFit = isoAlts.indexOfFirst { it.bytesPerInterval >= maxPayload }
                .let { if (it < 0) isoAlts.size - 1 else it }
            val candidates = isoAlts.subList(firstFit, isoAlts.size)
            var lastErr = 0
            var h = 0L
            for (alt in candidates) {
                setAlt(alt.altSetting)
                h = NativeUvc.nativeStart(
                    fd, alt.endpointAddress, true, alt.bytesPerInterval, maxPayload,
                    mode.format.pixelFormat.nativeId, mode.width, mode.height, maxFrameSize, err,
                )
                if (h != 0L) {
                    Log.i(TAG, "streaming iso alt=${alt.altSetting} bpi=${alt.bytesPerInterval}")
                    break
                }
                lastErr = err[0]
                Log.w(TAG, "iso alt ${alt.altSetting} failed: errno $lastErr")
            }
            if (h == 0L) {
                setAlt(0)
                throw UvcException("無法開始串流 (errno $lastErr)", lastErr)
            }
            handle = h
        } else {
            val bulk = streamIf.alts.firstOrNull { !it.isIso }
                ?: throw UvcException("找不到影像傳輸端點")
            handle = NativeUvc.nativeStart(
                fd, bulk.endpointAddress, false, 0, maxPayload,
                mode.format.pixelFormat.nativeId, mode.width, mode.height, maxFrameSize, err,
            )
            if (handle == 0L) throw UvcException("無法開始串流 (errno ${err[0]})", err[0])
            Log.i(TAG, "streaming bulk ep=${bulk.endpointAddress}")
        }
        return StreamSession(handle, mode).also { session = it }
    }

    fun stop() {
        session?.let {
            session = null
            it.close()
            setAlt(0)
        }
    }

    override fun close() {
        try {
            stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        claimed.forEach { connection.releaseInterface(it) }
        claimed.clear()
        connection.close()
    }

    // ---- UVC control requests ----

    private fun negotiate(mode: UvcMode): ByteArray {
        val preferred = when {
            info.uvcVersion >= 0x0150 -> 48
            info.uvcVersion >= 0x0110 -> 34
            else -> 26
        }
        val sizes = linkedSetOf(preferred, 34, 26, 48)
        for (size in sizes) {
            val buf = ByteArray(size)
            // Start from the device's current values when it offers them.
            control(GET_CUR, VS_PROBE_CONTROL, buf)
            put16(buf, 0, 1) // bmHint: keep dwFrameInterval
            buf[2] = mode.format.index.toByte()
            buf[3] = mode.frame.index.toByte()
            put32(buf, 4, mode.interval)
            if (control(SET_CUR, VS_PROBE_CONTROL, buf) < 0) continue
            val got = buf.copyOf()
            if (control(GET_CUR, VS_PROBE_CONTROL, got) < 0) {
                // Some devices do not answer GET_CUR; commit what we asked for.
                got.indices.forEach { got[it] = buf[it] }
            }
            if (control(SET_CUR, VS_COMMIT_CONTROL, got) < 0) continue
            if (u32(got, 18) == 0) put32(got, 18, maxOf(mode.frame.maxFrameBufferSize, mode.width * mode.height * 2).toLong())
            return got
        }
        throw UvcException("裝置不接受此解析度 (${mode.label})")
    }

    private fun control(request: Int, selector: Int, data: ByteArray): Int {
        val type = if (request == SET_CUR) REQ_TYPE_SET else REQ_TYPE_GET
        return connection.controlTransfer(
            type, request, selector shl 8, streamIf.interfaceNumber, data, data.size, CONTROL_TIMEOUT_MS,
        )
    }

    private fun setAlt(alt: Int) {
        val intf = findInterface(device, streamIf.interfaceNumber, alt) ?: return
        if (!connection.setInterface(intf)) Log.w(TAG, "setInterface alt=$alt failed")
    }

    private fun claim() {
        findInterface(device, info.controlInterface, 0)?.let {
            if (connection.claimInterface(it, true)) claimed += it
        }
        val vs = findInterface(device, streamIf.interfaceNumber, 0)
            ?: throw UvcException("找不到影像串流介面")
        if (!connection.claimInterface(vs, true)) throw UvcException("無法取得 USB 介面控制權 (可能被其他 App 使用中)")
        claimed += vs
    }

    companion object {
        private const val TAG = "UvcCamera"
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val REQ_TYPE_SET = 0x21 // host-to-device | class | interface
        private const val REQ_TYPE_GET = 0xA1 // device-to-host | class | interface
        private const val VS_PROBE_CONTROL = 0x01
        private const val VS_COMMIT_CONTROL = 0x02
        private const val CONTROL_TIMEOUT_MS = 1000
        private val COMMON_FPS = listOf(120.0, 60.0, 59.94, 50.0, 30.0, 29.97, 25.0, 24.0, 23.976, 20.0, 15.0, 10.0, 5.0)

        fun isVideoDevice(device: UsbDevice): Boolean = hasInterfaceClass(device, UsbConstants.USB_CLASS_VIDEO)

        fun isStillImageDevice(device: UsbDevice): Boolean =
            hasInterfaceClass(device, UsbConstants.USB_CLASS_STILL_IMAGE)

        private fun hasInterfaceClass(device: UsbDevice, cls: Int): Boolean =
            device.deviceClass == cls || (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == cls }

        fun open(manager: UsbManager, device: UsbDevice): UvcCamera {
            val conn = manager.openDevice(device) ?: throw UvcException("無法開啟 USB 裝置")
            try {
                val raw = conn.rawDescriptors ?: throw UvcException("讀取 USB 描述元失敗")
                val info = UvcDescriptorParser.parse(raw) ?: throw UvcException("這不是 UVC 影像裝置")
                val stream = info.streams.firstOrNull { it.formats.isNotEmpty() }
                    ?: throw UvcException("裝置沒有支援的影像格式 (支援 MJPEG / YUY2 / NV12)")
                return UvcCamera(device, conn, info, stream).also { it.claim() }
            } catch (e: Exception) {
                conn.close()
                throw e
            }
        }

        private fun findInterface(device: UsbDevice, number: Int, alt: Int): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.id == number && it.alternateSetting == alt }

        private fun u32(b: ByteArray, i: Int): Int =
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

        private fun put16(b: ByteArray, i: Int, v: Int) {
            b[i] = v.toByte()
            b[i + 1] = (v shr 8).toByte()
        }

        private fun put32(b: ByteArray, i: Int, v: Long) {
            for (k in 0 until 4) b[i + k] = (v shr (8 * k)).toByte()
        }
    }
}

/** A running stream. [waitFrame] is for a single consumer thread; [close] from any thread. */
class StreamSession internal constructor(private var handle: Long, val mode: UvcMode) : Closeable {
    private val lock = ReentrantReadWriteLock()
    private val statsBuf = LongArray(5)

    @Volatile
    var closed = false
        private set

    /** See [NativeUvc.nativeWaitFrame] for the return value. */
    fun waitFrame(dst: ByteArray, timeoutMs: Int): Int = lock.read {
        if (closed) -1 else NativeUvc.nativeWaitFrame(handle, dst, timeoutMs)
    }

    data class Stats(val frames: Long, val dropped: Long, val bytes: Long, val running: Boolean, val lastErrno: Int)

    fun stats(): Stats = lock.read {
        if (closed) return Stats(0, 0, 0, false, 0)
        synchronized(statsBuf) {
            NativeUvc.nativeStats(handle, statsBuf)
            Stats(statsBuf[0], statsBuf[1], statsBuf[2], statsBuf[3] != 0L, statsBuf[4].toInt())
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // Stop first (wakes a blocked waitFrame), then free once no reader is inside.
        NativeUvc.nativeStop(handle)
        lock.write {
            NativeUvc.nativeRelease(handle)
            handle = 0
        }
    }
}
