// Force-included (-include) into every readsb translation unit. Fills the gaps between
// glibc, which readsb targets, and Android's bionic libc.
#pragma once

#include <pthread.h>
#include <stdlib.h>

#if __ANDROID_API__ < 28
// aligned_alloc() only exists in bionic from API 28; posix_memalign() memory is free()-able too.
static inline void *readsb_aligned_alloc(size_t alignment, size_t size) {
    void *p = NULL;
    return posix_memalign(&p, alignment, size) == 0 ? p : NULL;
}
#define aligned_alloc readsb_aligned_alloc
#endif

// bionic has no pthread_tryjoin_np(). readsb only uses it to join worker threads at shutdown,
// polling until a timeout; a blocking join is fine because a hung shutdown ends in a process kill.
#define pthread_tryjoin_np(thread, retval) pthread_join((thread), (retval))

// readsb calls exit() on fatal errors and at the end of shutdown. Inside an app process that
// would run ART's atexit handlers while its threads are still live, so route it to the JNI
// bridge, which logs the exit code and calls _exit(). See jni_bridge.c.
_Noreturn void readsb_android_exit(int code);
#define exit(code) readsb_android_exit(code)

// Asked by readsb's JSON writer before each aircraft.json (see the patch in CMakeLists.txt).
// While the app is hidden (NativeFeeder.setJsonPaused) it says to skip all but one write a
// minute. stats.json is unaffected.
int readsb_android_skip_json(void);
