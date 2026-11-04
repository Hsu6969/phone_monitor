/*
 * Minimal UVC (USB Video Class) streaming engine.
 *
 * Java side (UvcCamera.kt) opens the device through Android's UsbManager,
 * parses descriptors, negotiates the stream with PROBE/COMMIT control requests
 * and selects the alternate setting. This file then takes the usbfs file
 * descriptor and does the part Android's Java API cannot: isochronous (and
 * bulk) URB streaming, UVC payload-header parsing and frame reassembly.
 *
 * Frames are handed to a single consumer (the render thread) through a
 * "latest frame wins" slot, so a slow decoder simply drops frames instead of
 * backing up the USB pipeline.
 */
#include <jni.h>
#include <pthread.h>
#include <poll.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define TAG "uvcstream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FMT_MJPEG 1
#define FMT_YUY2  2
#define FMT_NV12  3

#define UVC_FID 0x01
#define UVC_EOF 0x02
#define UVC_ERR 0x40

#define ISO_PACKETS_PER_URB 32
#define MAX_URBS 16
#define URB_MEMORY_BUDGET (12 * 1024 * 1024)
#define MAX_FRAME_BYTES (64 * 1024 * 1024)

/* Standard JPEG Huffman tables (ITU T.81 Annex K) as a single DHT segment.
 * Many UVC devices send "MJPEG" frames without DHT; Android's decoder needs it. */
static const uint8_t STD_DHT[] = {
    0xff, 0xc4, 0x01, 0xa2, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
    0x0b, 0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00,
    0x01, 0x7d, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51,
    0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52,
    0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26,
    0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47,
    0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67,
    0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
    0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
    0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
    0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
    0xf7, 0xf8, 0xf9, 0xfa, 0x01, 0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
    0x0b, 0x11, 0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04, 0x00, 0x01,
    0x02, 0x77, 0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07,
    0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33,
    0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19,
    0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46,
    0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66,
    0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85,
    0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3,
    0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba,
    0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8,
    0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
    0xf7, 0xf8, 0xf9, 0xfa,
};

typedef struct {
    int fd;
    int endpoint;
    int is_iso;
    int packet_size;
    int urb_buf_len;
    int num_urbs;
    int inflight;
    struct usbdevfs_urb *urbs[MAX_URBS];

    int format;
    int width;
    int height;

    pthread_t thread;
    int thread_started;
    volatile int stop_requested;

    /* Frame being assembled (stream thread only). */
    uint8_t *asm_buf;
    size_t asm_cap;
    size_t asm_len;
    int last_fid;
    int asm_error;

    /* Latest complete frame, guarded by lock. */
    pthread_mutex_t lock;
    pthread_cond_t cond;
    uint8_t *latest;
    size_t latest_cap;
    size_t latest_len;
    uint64_t latest_seq;
    uint64_t consumed_seq;
    int running;
    int last_error;
    int64_t frames;
    int64_t dropped;
    int64_t bytes;

    /* Consumer-only scratch for raw YUV frames. */
    uint8_t *scratch;
    size_t scratch_cap;
} stream_t;

static int ensure_cap(uint8_t **buf, size_t *cap, size_t need) {
    if (need <= *cap) return 0;
    size_t ncap = need + need / 2 + 4096;
    uint8_t *n = (uint8_t *) realloc(*buf, ncap);
    if (!n) return -1;
    *buf = n;
    *cap = ncap;
    return 0;
}

static size_t expected_raw_size(const stream_t *s) {
    size_t px = (size_t) s->width * (size_t) s->height;
    switch (s->format) {
        case FMT_YUY2: return px * 2;
        case FMT_NV12: return px * 3 / 2;
        default: return 0;
    }
}

static int frame_looks_valid(const stream_t *s, const uint8_t *buf, size_t len) {
    if (s->format == FMT_MJPEG) {
        return len >= 4 && buf[0] == 0xFF && buf[1] == 0xD8;
    }
    return len >= expected_raw_size(s);
}

/* Called by the stream thread when a frame boundary is reached. */
static void finish_frame(stream_t *s) {
    if (s->asm_len == 0) {
        s->asm_error = 0;
        return;
    }
    if (!s->asm_error && frame_looks_valid(s, s->asm_buf, s->asm_len)) {
        pthread_mutex_lock(&s->lock);
        /* Swap buffers: the assembled frame becomes "latest". */
        uint8_t *tb = s->latest;
        size_t tc = s->latest_cap;
        s->latest = s->asm_buf;
        s->latest_cap = s->asm_cap;
        s->latest_len = s->asm_len;
        s->asm_buf = tb;
        s->asm_cap = tc;
        s->latest_seq++;
        s->frames++;
        pthread_cond_broadcast(&s->cond);
        pthread_mutex_unlock(&s->lock);
    } else {
        s->dropped++;
    }
    s->asm_len = 0;
    s->asm_error = 0;
}

/* One UVC payload: header + data. */
static void process_payload(stream_t *s, const uint8_t *p, int len) {
    if (len < 2) return;
    int hlen = p[0];
    if (hlen < 2 || hlen > 12 || hlen > len) return;
    uint8_t flags = p[1];
    int fid = flags & UVC_FID;

    if (s->last_fid >= 0 && fid != s->last_fid && s->asm_len > 0) {
        /* FID toggled without EOF: previous frame is done. */
        finish_frame(s);
    }
    s->last_fid = fid;
    if (flags & UVC_ERR) s->asm_error = 1;

    int dlen = len - hlen;
    if (dlen > 0) {
        if (s->asm_len + (size_t) dlen > MAX_FRAME_BYTES ||
            ensure_cap(&s->asm_buf, &s->asm_cap, s->asm_len + (size_t) dlen) != 0) {
            s->asm_len = 0;
            s->asm_error = 1;
        } else {
            memcpy(s->asm_buf + s->asm_len, p + hlen, (size_t) dlen);
            s->asm_len += (size_t) dlen;
        }
        s->bytes += dlen;
    }
    if (flags & UVC_EOF) finish_frame(s);
}

static void process_urb(stream_t *s, struct usbdevfs_urb *u) {
    if (s->is_iso) {
        for (int i = 0; i < u->number_of_packets; i++) {
            struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
            if (d->actual_length == 0) continue;
            process_payload(s, (const uint8_t *) u->buffer + (size_t) i * s->packet_size,
                            (int) d->actual_length);
        }
    } else if (u->status == 0) {
        process_payload(s, (const uint8_t *) u->buffer, u->actual_length);
    }
}

static void reset_urb(stream_t *s, struct usbdevfs_urb *u) {
    u->status = 0;
    u->actual_length = 0;
    u->error_count = 0;
    u->start_frame = 0;
    u->signr = 0;
    u->usercontext = s;
    u->buffer_length = s->urb_buf_len;
    if (s->is_iso) {
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->number_of_packets = ISO_PACKETS_PER_URB;
        for (int i = 0; i < ISO_PACKETS_PER_URB; i++) {
            u->iso_frame_desc[i].length = (unsigned int) s->packet_size;
            u->iso_frame_desc[i].actual_length = 0;
            u->iso_frame_desc[i].status = 0;
        }
    } else {
        u->type = USBDEVFS_URB_TYPE_BULK;
        u->flags = 0;
        u->number_of_packets = 0;
    }
    u->endpoint = (unsigned char) s->endpoint;
}

static int submit_urb(stream_t *s, struct usbdevfs_urb *u) {
    reset_urb(s, u);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    s->inflight++;
    return 0;
}

static void set_error(stream_t *s, int err) {
    pthread_mutex_lock(&s->lock);
    s->last_error = err;
    pthread_mutex_unlock(&s->lock);
}

static void cancel_all_urbs(stream_t *s) {
    for (int i = 0; i < s->num_urbs; i++) {
        if (s->urbs[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    /* Reap what is still pending, but never block forever. */
    for (int tries = 0; s->inflight > 0 && tries < 20; tries++) {
        struct pollfd pfd = {s->fd, POLLOUT | POLLWRNORM, 0};
        poll(&pfd, 1, 50);
        for (;;) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) break;
            s->inflight--;
        }
    }
    if (s->inflight > 0) LOGW("%d URBs not reaped at stop", s->inflight);
}

static void *stream_thread(void *arg) {
    stream_t *s = (stream_t *) arg;
    LOGI("stream thread started (iso=%d ep=0x%02x pkt=%d urbs=%d)",
         s->is_iso, s->endpoint, s->packet_size, s->num_urbs);
    while (!s->stop_requested) {
        struct pollfd pfd = {s->fd, POLLOUT | POLLWRNORM, 0};
        int pr = poll(&pfd, 1, 100);
        if (pr < 0) {
            if (errno == EINTR) continue;
            set_error(s, errno);
            break;
        }
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            set_error(s, ENODEV);
            break;
        }
        int fatal = 0;
        for (;;) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
                if (errno != EAGAIN) {
                    set_error(s, errno);
                    fatal = 1;
                }
                break;
            }
            s->inflight--;
            if (u->status == -ENODEV || u->status == -ESHUTDOWN) {
                set_error(s, ENODEV);
                fatal = 1;
                break;
            }
            process_urb(s, u);
            if (s->stop_requested) continue;
            int r = submit_urb(s, u);
            if (r < 0) {
                set_error(s, -r);
                if (r == -ENODEV) {
                    fatal = 1;
                    break;
                }
            }
        }
        if (fatal || s->inflight <= 0) {
            if (!fatal) set_error(s, EIO);
            break;
        }
    }
    cancel_all_urbs(s);
    pthread_mutex_lock(&s->lock);
    s->running = 0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    LOGI("stream thread exit (frames=%lld dropped=%lld err=%d)",
         (long long) s->frames, (long long) s->dropped, s->last_error);
    return NULL;
}

/* ---------- consumer side ---------- */

static size_t mjpeg_needs_dht(const uint8_t *b, size_t len) {
    size_t i = 2;
    while (i + 4 <= len) {
        if (b[i] != 0xFF) return 0;          /* malformed: leave as is */
        uint8_t m = b[i + 1];
        if (m == 0xFF) { i++; continue; }
        if (m == 0xC4) return 0;             /* has DHT */
        if (m == 0xDA) return 1;             /* reached SOS without DHT */
        if ((m >= 0xD0 && m <= 0xD9) || m == 0x01) { i += 2; continue; }
        i += 2 + (((size_t) b[i + 2] << 8) | b[i + 3]);
    }
    return 0;
}

static inline uint8_t clamp8(int v) {
    return (uint8_t) (v < 0 ? 0 : (v > 255 ? 255 : v));
}

static inline void yuv_to_rgba(int y, int u, int v, uint8_t *o) {
    int c = 298 * (y - 16);
    int d = u - 128;
    int e = v - 128;
    o[0] = clamp8((c + 409 * e + 128) >> 8);
    o[1] = clamp8((c - 100 * d - 208 * e + 128) >> 8);
    o[2] = clamp8((c + 516 * d + 128) >> 8);
    o[3] = 0xFF;
}

static void yuy2_to_rgba(const uint8_t *src, uint8_t *dst, int w, int h) {
    size_t pairs = (size_t) w * h / 2;
    for (size_t i = 0; i < pairs; i++) {
        const uint8_t *p = src + i * 4;
        yuv_to_rgba(p[0], p[1], p[3], dst);
        yuv_to_rgba(p[2], p[1], p[3], dst + 4);
        dst += 8;
    }
}

static void nv12_to_rgba(const uint8_t *src, uint8_t *dst, int w, int h) {
    const uint8_t *uv = src + (size_t) w * h;
    for (int y = 0; y < h; y++) {
        const uint8_t *yr = src + (size_t) y * w;
        const uint8_t *uvr = uv + (size_t) (y / 2) * w;
        uint8_t *o = dst + (size_t) y * w * 4;
        for (int x = 0; x < w; x++) {
            yuv_to_rgba(yr[x], uvr[x & ~1], uvr[(x & ~1) + 1], o + (size_t) x * 4);
        }
    }
}

/* Size of the output a frame of len bytes needs in the consumer's buffer. */
static size_t output_size(const stream_t *s, const uint8_t *frame, size_t len) {
    if (s->format == FMT_MJPEG) return len + (mjpeg_needs_dht(frame, len) ? sizeof(STD_DHT) : 0);
    return (size_t) s->width * s->height * 4;
}

/*
 * Wait for a frame newer than the last one returned and write it to dst.
 * Returns: >0 bytes written, 0 timeout, -1 stream stopped,
 * < -1 the dst buffer is too small and -return is the size needed.
 */
static long wait_frame_into(stream_t *s, uint8_t *dst, size_t cap, int timeout_ms) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeout_ms / 1000;
    ts.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&s->lock);
    while (s->running && s->latest_seq == s->consumed_seq) {
        if (pthread_cond_timedwait(&s->cond, &s->lock, &ts) == ETIMEDOUT) break;
    }
    if (s->latest_seq == s->consumed_seq) {
        int running = s->running;
        pthread_mutex_unlock(&s->lock);
        return running ? 0 : -1;
    }
    size_t need = output_size(s, s->latest, s->latest_len);
    if (need > cap) {
        pthread_mutex_unlock(&s->lock);
        return -(long) (need < 2 ? 2 : need);
    }
    s->consumed_seq = s->latest_seq;
    if (s->format == FMT_MJPEG) {
        size_t len = s->latest_len;
        if (need > len) {
            dst[0] = 0xFF;
            dst[1] = 0xD8;
            memcpy(dst + 2, STD_DHT, sizeof(STD_DHT));
            memcpy(dst + 2 + sizeof(STD_DHT), s->latest + 2, len - 2);
        } else {
            memcpy(dst, s->latest, len);
        }
        pthread_mutex_unlock(&s->lock);
        return (long) need;
    }
    /* Raw YUV: copy out under the lock, convert outside it. */
    size_t raw = expected_raw_size(s);
    if (ensure_cap(&s->scratch, &s->scratch_cap, raw) != 0) {
        pthread_mutex_unlock(&s->lock);
        return 0;
    }
    memcpy(s->scratch, s->latest, raw);
    pthread_mutex_unlock(&s->lock);
    if (s->format == FMT_YUY2) yuy2_to_rgba(s->scratch, dst, s->width, s->height);
    else nv12_to_rgba(s->scratch, dst, s->width, s->height);
    return (long) need;
}

static stream_t *stream_alloc(int format, int width, int height) {
    stream_t *s = (stream_t *) calloc(1, sizeof(stream_t));
    if (!s) return NULL;
    s->format = format;
    s->width = width;
    s->height = height;
    s->last_fid = -1;
    s->fd = -1;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->cond, NULL);
    return s;
}

static void stream_free(stream_t *s) {
    for (int i = 0; i < MAX_URBS; i++) {
        if (s->urbs[i]) {
            free(s->urbs[i]->buffer);
            free(s->urbs[i]);
        }
    }
    free(s->asm_buf);
    free(s->latest);
    free(s->scratch);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->cond);
    free(s);
}

static void stream_stop(stream_t *s) {
    s->stop_requested = 1;
    if (s->thread_started) {
        pthread_join(s->thread, NULL);
        s->thread_started = 0;
    }
    pthread_mutex_lock(&s->lock);
    s->running = 0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
}

/* ---------- JNI ---------- */

JNIEXPORT jlong JNICALL
Java_com_phonemonitor_app_uvc_NativeUvc_nativeStart(JNIEnv *env, jclass clazz, jint fd,
                                                    jint endpoint, jboolean is_iso,
                                                    jint packet_size, jint max_payload,
                                                    jint format, jint width, jint height,
                                                    jint max_frame_size) {
    (void) env;
    (void) clazz;
    stream_t *s = stream_alloc(format, width, height);
    if (!s) return -ENOMEM;
    s->fd = fd;
    s->endpoint = endpoint;
    s->is_iso = is_iso ? 1 : 0;

    if (s->is_iso) {
        if (packet_size <= 0) {
            stream_free(s);
            return -EINVAL;
        }
        s->packet_size = packet_size;
        s->urb_buf_len = packet_size * ISO_PACKETS_PER_URB;
    } else {
        int len = max_payload > 0 ? max_payload : max_frame_size;
        if (len < 16384) len = 16384;
        if (len > 6 * 1024 * 1024) len = 6 * 1024 * 1024;
        s->packet_size = len;
        s->urb_buf_len = len;
    }
    int n = URB_MEMORY_BUDGET / s->urb_buf_len;
    if (n > MAX_URBS) n = MAX_URBS;
    if (n < 2) n = 2;
    s->num_urbs = n;

    size_t initial = max_frame_size > 0 ? (size_t) max_frame_size : (size_t) width * height * 2;
    if (initial > MAX_FRAME_BYTES) initial = MAX_FRAME_BYTES;
    if (ensure_cap(&s->asm_buf, &s->asm_cap, initial) != 0 ||
        ensure_cap(&s->latest, &s->latest_cap, initial) != 0) {
        stream_free(s);
        return -ENOMEM;
    }

    size_t urb_size = sizeof(struct usbdevfs_urb) +
                      (s->is_iso ? ISO_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc) : 0);
    for (int i = 0; i < s->num_urbs; i++) {
        struct usbdevfs_urb *u = (struct usbdevfs_urb *) calloc(1, urb_size);
        void *buf = malloc((size_t) s->urb_buf_len);
        if (!u || !buf) {
            free(u);
            free(buf);
            break;
        }
        u->buffer = buf;
        s->urbs[i] = u;
    }

    int submitted = 0;
    int last_err = 0;
    for (int i = 0; i < s->num_urbs; i++) {
        if (!s->urbs[i]) continue;
        int r = submit_urb(s, s->urbs[i]);
        if (r < 0) {
            last_err = r;
            LOGW("submit URB %d failed: %d", i, r);
            break;
        }
        submitted++;
    }
    if (submitted == 0) {
        LOGE("no URB could be submitted (err %d)", last_err);
        stream_free(s);
        return last_err ? last_err : -EIO;
    }

    s->running = 1;
    if (pthread_create(&s->thread, NULL, stream_thread, s) != 0) {
        cancel_all_urbs(s);
        stream_free(s);
        return -EAGAIN;
    }
    s->thread_started = 1;
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL
Java_com_phonemonitor_app_uvc_NativeUvc_nativeWaitFrame(JNIEnv *env, jclass clazz, jlong handle,
                                                        jbyteArray dst, jint timeout_ms) {
    (void) clazz;
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (!s) return -1;
    jsize cap = (*env)->GetArrayLength(env, dst);
    uint8_t *p = (uint8_t *) (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!p) return 0;
    long r = wait_frame_into(s, p, (size_t) cap, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, p, r > 0 ? 0 : JNI_ABORT);
    if (r != 0) return (jint) r;
    /* Nothing ready: block outside the JNI critical section. */
    pthread_mutex_lock(&s->lock);
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeout_ms / 1000;
    ts.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000L;
    }
    while (s->running && s->latest_seq == s->consumed_seq) {
        if (pthread_cond_timedwait(&s->cond, &s->lock, &ts) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&s->lock);
    p = (uint8_t *) (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (!p) return 0;
    r = wait_frame_into(s, p, (size_t) cap, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, dst, p, r > 0 ? 0 : JNI_ABORT);
    return (jint) r;
}

JNIEXPORT void JNICALL
Java_com_phonemonitor_app_uvc_NativeUvc_nativeStats(JNIEnv *env, jclass clazz, jlong handle,
                                                    jlongArray out) {
    (void) clazz;
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (!s) return;
    jlong v[5];
    pthread_mutex_lock(&s->lock);
    v[0] = s->frames;
    v[1] = s->dropped;
    v[2] = s->bytes;
    v[3] = s->running;
    v[4] = s->last_error;
    pthread_mutex_unlock(&s->lock);
    jsize n = (*env)->GetArrayLength(env, out);
    (*env)->SetLongArrayRegion(env, out, 0, n < 5 ? n : 5, v);
}

JNIEXPORT void JNICALL
Java_com_phonemonitor_app_uvc_NativeUvc_nativeStop(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (s) stream_stop(s);
}

JNIEXPORT void JNICALL
Java_com_phonemonitor_app_uvc_NativeUvc_nativeRelease(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (s) {
        stream_stop(s);
        stream_free(s);
    }
}
