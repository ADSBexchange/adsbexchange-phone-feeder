#include "usb_shim.h"

#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>

#define TAG "usb_shim"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// librtlsdr creates a fresh libusb context for each call (count, strings, open). Every
// get_device_list wraps the fd into a new handle in that context; the handle is either
// handed to librtlsdr by shim_libusb_open or closed when the context exits.
#define MAX_WRAPPED 8

struct wrapped {
    libusb_context *ctx;
    libusb_device_handle *handle;
    int handed_out;
};

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct wrapped g_wrapped[MAX_WRAPPED];
static int g_fd = -1;
static pthread_once_t g_options_once = PTHREAD_ONCE_INIT;

static void set_default_options(void) {
    // Applies to every context created afterwards: skip /dev/bus/usb scanning.
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
}

void usb_shim_set_fd(int fd) {
    pthread_mutex_lock(&g_lock);
    g_fd = fd;
    pthread_mutex_unlock(&g_lock);
}

int shim_libusb_init(libusb_context **ctx) {
    pthread_once(&g_options_once, set_default_options);
    return libusb_init(ctx);
}

ssize_t shim_libusb_get_device_list(libusb_context *ctx, libusb_device ***list) {
    libusb_device **out = calloc(2, sizeof(*out));
    if (!out)
        return LIBUSB_ERROR_NO_MEM;
    *list = out;

    pthread_mutex_lock(&g_lock);
    int fd = g_fd;
    int slot = -1;
    for (int i = 0; i < MAX_WRAPPED; i++) {
        if (!g_wrapped[i].handle) {
            slot = i;
            break;
        }
    }
    ssize_t count = 0;
    if (fd < 0) {
        LOGE("no USB fd set; call usb_shim_set_fd() first");
    } else if (slot < 0) {
        LOGE("too many wrapped handles");
    } else {
        libusb_device_handle *handle = NULL;
        int r = libusb_wrap_sys_device(ctx, (intptr_t) fd, &handle);
        if (r != LIBUSB_SUCCESS) {
            LOGE("libusb_wrap_sys_device(fd=%d) failed: %s", fd, libusb_error_name(r));
        } else {
            g_wrapped[slot] = (struct wrapped) {.ctx = ctx, .handle = handle, .handed_out = 0};
            // The list owns a reference, released by libusb_free_device_list(list, 1).
            out[0] = libusb_ref_device(libusb_get_device(handle));
            count = 1;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return count;
}

int shim_libusb_open(libusb_device *dev, libusb_device_handle **dev_handle) {
    int r = LIBUSB_ERROR_NO_DEVICE;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_WRAPPED; i++) {
        struct wrapped *w = &g_wrapped[i];
        if (w->handle && !w->handed_out && libusb_get_device(w->handle) == dev) {
            w->handed_out = 1;
            *dev_handle = w->handle;
            r = LIBUSB_SUCCESS;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return r;
}

void shim_libusb_close(libusb_device_handle *dev_handle) {
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_WRAPPED; i++) {
        if (g_wrapped[i].handle == dev_handle)
            g_wrapped[i] = (struct wrapped) {0};
    }
    pthread_mutex_unlock(&g_lock);
    libusb_close(dev_handle);
}

void shim_libusb_exit(libusb_context *ctx) {
    // Close handles that were enumerated in this context but never opened.
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_WRAPPED; i++) {
        struct wrapped *w = &g_wrapped[i];
        if (w->handle && w->ctx == ctx && !w->handed_out) {
            libusb_close(w->handle);
            *w = (struct wrapped) {0};
        }
    }
    pthread_mutex_unlock(&g_lock);
    libusb_exit(ctx);
}
