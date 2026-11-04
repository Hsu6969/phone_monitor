package com.phonemonitor.app.uvc

/** JNI bindings to libuvcstream (app/src/main/cpp/uvc_stream.c). */
internal object NativeUvc {
    init {
        System.loadLibrary("uvcstream")
    }

    /**
     * Returns a non-zero handle, or 0 on failure with the errno in errOut[0].
     * The handle may be negative (tagged heap pointer on arm64); only 0 means failure.
     */
    @JvmStatic
    external fun nativeStart(
        fd: Int,
        endpoint: Int,
        isIso: Boolean,
        packetSize: Int,
        maxPayload: Int,
        format: Int,
        width: Int,
        height: Int,
        maxFrameSize: Int,
        errOut: IntArray,
    ): Long

    /**
     * Waits for a new frame and copies it into [dst].
     * Returns bytes written, 0 on timeout, -1 when the stream has stopped,
     * or a value < -1 whose negation is the buffer size required.
     * MJPEG frames are returned as JPEG; YUY2/NV12 frames as RGBA_8888 pixels.
     */
    @JvmStatic
    external fun nativeWaitFrame(handle: Long, dst: ByteArray, timeoutMs: Int): Int

    /** out = [frames, dropped, bytes, running(0/1), lastErrno] */
    @JvmStatic
    external fun nativeStats(handle: Long, out: LongArray)

    @JvmStatic
    external fun nativeStop(handle: Long)

    @JvmStatic
    external fun nativeRelease(handle: Long)
}
