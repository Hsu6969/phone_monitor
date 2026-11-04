package com.phonemonitor.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import com.phonemonitor.app.uvc.PixelFormat
import com.phonemonitor.app.uvc.StreamSession
import java.nio.ByteBuffer

enum class ScaleMode { FIT, FILL }

/**
 * Pulls the newest frame from the active [StreamSession], decodes it and draws
 * it on the SurfaceView. Runs on its own thread while the surface exists.
 */
class FrameRenderer(private val holder: SurfaceHolder, private val listener: Listener) {

    interface Listener {
        /** Called about once per second from the render thread. */
        fun onFrameStats(session: StreamSession, displayFps: Double, width: Int, height: Int)

        /** The session's native stream stopped on its own (cable pulled, USB error...). */
        fun onStreamEnded(session: StreamSession)

        /**
         * The picture became (or stopped being) a single flat color, e.g. the camera
         * replaces the video with a gray frame while its menu is open.
         */
        fun onFlatFrameChanged(flat: Boolean)
    }

    @Volatile
    var session: StreamSession? = null

    @Volatile
    var scaleMode = ScaleMode.FIT

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "FrameRenderer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            it.join(1000)
        }
        thread = null
    }

    private fun loop() {
        var buf = ByteArray(1 shl 20)
        // Two bitmaps used alternately so we never overwrite one the GPU may still be reading.
        val bitmaps = arrayOfNulls<Bitmap>(2)
        var which = 0
        var lastSession: StreamSession? = null
        var endedReported: StreamSession? = null
        var frameCount = 0
        var windowStart = SystemClock.elapsedRealtime()
        var clearedIdle = false
        var flatCount = 0
        var flatReported = false

        while (running) {
            val s = session
            if (s == null) {
                if (!clearedIdle) clearedIdle = drawBlack()
                sleepQuietly(30)
                continue
            }
            clearedIdle = false
            if (s !== lastSession) {
                lastSession = s
                flatCount = 0
                if (flatReported) {
                    flatReported = false
                    listener.onFlatFrameChanged(false)
                }
                frameCount = 0
                windowStart = SystemClock.elapsedRealtime()
            }
            val n = try {
                s.waitFrame(buf, 200)
            } catch (e: Exception) {
                Log.w(TAG, "waitFrame failed", e)
                -1
            }
            when {
                n == 0 -> continue
                n == -1 -> {
                    if (!s.closed && endedReported !== s) {
                        endedReported = s
                        listener.onStreamEnded(s)
                    }
                    sleepQuietly(30)
                    continue
                }
                n < -1 -> {
                    buf = ByteArray(-n + (64 shl 10))
                    continue
                }
            }

            which = which xor 1
            val bmp = decode(s, buf, n, bitmaps[which]) ?: continue
            bitmaps[which] = bmp
            draw(bmp)

            // Debounced flat-frame detection (3 consecutive flat frames to enter, 1 normal to leave).
            if (isFlat(bmp)) flatCount++ else flatCount = 0
            val flat = flatCount >= 3
            if (flat != flatReported) {
                flatReported = flat
                listener.onFlatFrameChanged(flat)
            }

            frameCount++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 1000) {
                listener.onFrameStats(s, frameCount * 1000.0 / (now - windowStart), bmp.width, bmp.height)
                frameCount = 0
                windowStart = now
            }
        }
    }

    private fun decode(s: StreamSession, buf: ByteArray, n: Int, reuse: Bitmap?): Bitmap? {
        val mode = s.mode
        if (mode.format.pixelFormat != PixelFormat.MJPEG) {
            val bmp = reuse?.takeIf { it.width == mode.width && it.height == mode.height && it.isMutable }
                ?: Bitmap.createBitmap(mode.width, mode.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(buf, 0, mode.width * mode.height * 4))
            return bmp
        }
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(buf, 0, n, opts)
        } catch (e: IllegalArgumentException) {
            // reuse bitmap not compatible (size changed): decode into a fresh one
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(buf, 0, n, opts)
        }
    }

    /** Samples a 9x9 grid; flat when every channel varies by at most a few levels. */
    private fun isFlat(bmp: Bitmap): Boolean {
        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        for (gy in 1..9) {
            val y = bmp.height * gy / 10
            for (gx in 1..9) {
                val c = bmp.getPixel(bmp.width * gx / 10, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
        }
        return maxR - minR <= FLAT_TOLERANCE && maxG - minG <= FLAT_TOLERANCE && maxB - minB <= FLAT_TOLERANCE
    }

    private fun draw(bmp: Bitmap) {
        val canvas = lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            val scale = when (scaleMode) {
                ScaleMode.FIT -> minOf(cw / bmp.width, ch / bmp.height)
                ScaleMode.FILL -> maxOf(cw / bmp.width, ch / bmp.height)
            }
            val w = bmp.width * scale
            val h = bmp.height * scale
            dst.set((cw - w) / 2f, (ch - h) / 2f, (cw + w) / 2f, (ch + h) / 2f)
            canvas.drawBitmap(bmp, null, dst, paint)
        } finally {
            unlock(canvas)
        }
    }

    private fun drawBlack(): Boolean {
        val canvas = lockCanvas() ?: return false
        try {
            canvas.drawColor(Color.BLACK)
        } finally {
            unlock(canvas)
        }
        return true
    }

    private fun lockCanvas(): Canvas? = try {
        holder.lockHardwareCanvas()
    } catch (e: Exception) {
        null
    }

    private fun unlock(canvas: Canvas) {
        try {
            holder.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "unlockCanvasAndPost failed", e)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "FrameRenderer"
        private const val FLAT_TOLERANCE = 6
    }
}
