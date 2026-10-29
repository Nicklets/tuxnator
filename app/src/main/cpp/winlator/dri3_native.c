// Native helpers for DRI3 clients outside Wine (Mesa's X11 WSI in the Linux rootfs):
// xshmfence-compatible fences and dma-buf inspection.
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <jni.h>
#include <linux/dma-buf.h>
#include <linux/futex.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define LOG_TAG "Dri3Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Layout and protocol of libxshmfence's futex implementation (xshmfence_futex.h):
// v == 0 untriggered, 1 triggered, -1 untriggered with waiters.
struct xshmfence {
    int32_t v;
};

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_extensions_SyncExtension_nativeMapShmFence(JNIEnv *env, jclass clazz, jint fd) {
    void *addr = mmap(NULL, sizeof(struct xshmfence), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (addr == MAP_FAILED) {
        LOGE("nativeMapShmFence: mmap failed");
        return 0;
    }
    return (jlong)(intptr_t)addr;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_SyncExtension_nativeTriggerShmFence(JNIEnv *env, jclass clazz, jlong ptr) {
    struct xshmfence *f = (struct xshmfence *)(intptr_t)ptr;
    if (!f) return;
    // Same as xshmfence_trigger()
    if (__sync_val_compare_and_swap(&f->v, 0, 1) != 0) {
        if (__sync_val_compare_and_swap(&f->v, -1, 1) == -1)
            syscall(SYS_futex, &f->v, FUTEX_WAKE, INT32_MAX, NULL, NULL, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_SyncExtension_nativeResetShmFence(JNIEnv *env, jclass clazz, jlong ptr) {
    struct xshmfence *f = (struct xshmfence *)(intptr_t)ptr;
    if (f) __sync_bool_compare_and_swap(&f->v, 1, 0);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_SyncExtension_nativeUnmapShmFence(JNIEnv *env, jclass clazz, jlong ptr) {
    if (ptr) munmap((void *)(intptr_t)ptr, sizeof(struct xshmfence));
}

// A linear dma-buf (Mesa DRI3 swapchain image) mapped for reading
struct dmabuf_map {
    int fd;
    uint8_t *ptr;
    size_t size;
};

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_extensions_DRI3Extension_nativeMapDmaBuf(JNIEnv *env, jclass clazz, jint fd, jint size) {
    // The caller closes its fd after the request, so keep our own for the sync ioctls
    int own_fd = dup(fd);
    if (own_fd < 0) return 0;
    void *ptr = mmap(NULL, size, PROT_READ, MAP_SHARED, own_fd, 0);
    if (ptr == MAP_FAILED) {
        LOGE("nativeMapDmaBuf: mmap of %d bytes failed", size);
        close(own_fd);
        return 0;
    }
    struct dmabuf_map *map = malloc(sizeof(*map));
    map->fd = own_fd;
    map->ptr = ptr;
    map->size = size;
    return (jlong)(intptr_t)map;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_DRI3Extension_nativeUnmapDmaBuf(JNIEnv *env, jclass clazz, jlong handle) {
    struct dmabuf_map *map = (struct dmabuf_map *)(intptr_t)handle;
    if (!map) return;
    munmap(map->ptr, map->size);
    close(map->fd);
    free(map);
}

// Copies the client's frame into an AHardwareBuffer-backed drawable: one copy instead of Mesa's
// software path (render -> CPU copy -> PutImage over the socket -> copy into the drawable).
JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_extensions_DRI3Extension_nativeCopyDmaBufToAHB(JNIEnv *env, jclass clazz, jlong handle,
                                                                             jint srcOffset, jint srcStride, jshort width, jshort height,
                                                                             jlong dstAHB, jshort dstStridePixels) {
    struct dmabuf_map *map = (struct dmabuf_map *)(intptr_t)handle;
    AHardwareBuffer *ahb = (AHardwareBuffer *)(intptr_t)dstAHB;
    if (!map || !ahb) return;

    uint8_t *dst = NULL;
    if (AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **)&dst) != 0 || !dst) {
        LOGE("nativeCopyDmaBufToAHB: lock failed");
        return;
    }

    struct dma_buf_sync sync = { .flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ };
    ioctl(map->fd, DMA_BUF_IOCTL_SYNC, &sync);

    size_t row = (size_t)width * 4;
    size_t dstStride = (size_t)dstStridePixels * 4;
    const uint8_t *src = map->ptr + srcOffset;
    for (int y = 0; y < height; y++) {
        if ((size_t)srcOffset + (size_t)y * srcStride + row > map->size) break;
        memcpy(dst + y * dstStride, src + (size_t)y * srcStride, row);
    }

    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    ioctl(map->fd, DMA_BUF_IOCTL_SYNC, &sync);
    AHardwareBuffer_unlock(ahb, NULL);
}

// AHardwareBuffers from the Wine wrapper arrive as a socket to receive the handle from; Mesa's
// DRI3 sends the dma-buf itself.
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_xserver_extensions_DRI3Extension_nativeIsSocket(JNIEnv *env, jclass clazz, jint fd) {
    struct stat st;
    if (fstat(fd, &st) != 0) return JNI_FALSE;
    return S_ISSOCK(st.st_mode) ? JNI_TRUE : JNI_FALSE;
}
