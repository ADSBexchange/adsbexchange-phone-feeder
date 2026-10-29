// JNI entry points for com.adsbexchange.feeder.NativeFeeder.
//
// readsb runs on its own thread inside the app's dedicated ":feeder" process. It was written
// as a program, not a library: it keeps global state and ends by calling exit(). So it can be
// started once per process, and when it finishes the whole :feeder process ends.

#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "usb_shim.h"

#define TAG "readsb"
#define MAX_LOG_BYTES (512 * 1024)

int readsb_main(int argc, char **argv);   // readsb.c, renamed from main()
void readsb_request_exit(void);           // readsb_android_glue.inc

static atomic_int g_started;
static atomic_int g_pump_done;
static atomic_int g_json_paused;
// Copy readsb's output to logcat too (debug builds only: setLogcatMirror). Release builds keep it to
// readsb.log, which the app redacts before showing or sharing; logcat ends up in bug reports and is
// readable over adb. Off unless turned on, so a start path that forgets to set it stays private
// (security review 2026-10-11, C5).
static atomic_int g_logcat_mirror = 0;
static time_t g_last_paused_json; // touched only by readsb's JSON writer thread

// How often aircraft.json is written while nobody looks: once a minute for the history, or every
// 5 s while stats uploads need it (see setHiddenJsonInterval).
static atomic_int g_paused_json_interval_s = 60;

// See android_compat.h. Called by readsb's JSON writer thread about once a second.
int readsb_android_skip_json(void) {
    if (!atomic_load(&g_json_paused))
        return 0;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec - g_last_paused_json < atomic_load(&g_paused_json_interval_s))
        return 1;
    g_last_paused_json = now.tv_sec;
    return 0;
}
static int g_log_fd = -1;
static char g_exit_path[512];

struct readsb_args {
    int argc;
    char **argv;
};

// Copies readsb's stdout/stderr to a log file the UI tails, and to logcat in debug builds.
static void *log_pump(void *arg) {
    int pipe_fd = (int) (intptr_t) arg;
    char buf[4096];
    char line[1024];
    size_t line_len = 0;
    ssize_t n;
    while ((n = read(pipe_fd, buf, sizeof(buf))) > 0) {
        struct stat st;
        if (fstat(g_log_fd, &st) == 0 && st.st_size > MAX_LOG_BYTES)
            ftruncate(g_log_fd, 0);
        write(g_log_fd, buf, (size_t) n);

        if (!atomic_load(&g_logcat_mirror))
            continue;
        for (ssize_t i = 0; i < n; i++) {
            // A full line buffer is flushed first, so the byte that filled it isn't lost.
            if (line_len == sizeof(line) - 1) {
                line[line_len] = '\0';
                __android_log_write(ANDROID_LOG_INFO, TAG, line);
                line_len = 0;
            }
            if (buf[i] == '\n') {
                line[line_len] = '\0';
                __android_log_write(ANDROID_LOG_INFO, TAG, line);
                line_len = 0;
            } else {
                line[line_len++] = buf[i];
            }
        }
    }
    atomic_store(&g_pump_done, 1); // EOF: every write end of the pipe is closed
    return NULL;
}

static int redirect_stdio(const char *log_path) {
    g_log_fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0600);
    if (g_log_fd < 0)
        return -1;
    int fds[2];
    if (pipe(fds) != 0)
        return -1;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    pthread_t t;
    if (pthread_create(&t, NULL, log_pump, (void *) (intptr_t) fds[0]) != 0)
        return -1;
    pthread_detach(t);
    return 0;
}

_Noreturn void readsb_android_exit(int code) {
    fflush(stdout);
    fflush(stderr);
    // readsb's last lines ("Normal exit." etc.) may still be in the pipe. Point stdout/stderr at
    // /dev/null, which closes the pipe's only write ends, then give the pump up to 1 s to drain
    // it and see EOF before the process goes away.
    int devnull = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (devnull >= 0 && g_log_fd >= 0) {
        dup2(devnull, STDOUT_FILENO);
        dup2(devnull, STDERR_FILENO);
        for (int i = 0; i < 100 && !atomic_load(&g_pump_done); i++)
            usleep(10 * 1000);
    }
    char msg[64];
    int len = snprintf(msg, sizeof(msg), "[feeder] readsb exited with code %d\n", code);
    if (g_log_fd >= 0)
        write(g_log_fd, msg, (size_t) len);
    __android_log_print(ANDROID_LOG_WARN, TAG, "readsb exited with code %d", code);
    // Tell the UI process how readsb ended (it can't observe this process directly).
    if (g_exit_path[0]) {
        int fd = open(g_exit_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) {
            len = snprintf(msg, sizeof(msg), "%d\n", code);
            write(fd, msg, (size_t) len);
            close(fd);
        }
    }
    _exit(code);
}

static void *readsb_thread(void *arg) {
    struct readsb_args *a = arg;
    int code = readsb_main(a->argc, a->argv);
    readsb_android_exit(code); // normally unreachable: readsb exits via cleanup_and_exit()
}

JNIEXPORT jint JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_start(JNIEnv *env, jclass clazz, jint usb_fd,
                                          jobjectArray args, jstring log_path,
                                          jstring exit_path) {
    (void) clazz;
    if (atomic_exchange(&g_started, 1))
        return -1; // readsb's globals can't be reinitialised; restart the process instead

    // Allocations are checked: out of memory, this returns -4 rather than crashing (security
    // report MF-SEC-11), and the service stops with "couldn't start".
    const char *path = (*env)->GetStringUTFChars(env, log_path, NULL);
    if (!path)
        return -4;
    int r = redirect_stdio(path);
    (*env)->ReleaseStringUTFChars(env, log_path, path);
    if (r != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "could not redirect stdio");
        return -2;
    }

    const char *ep = (*env)->GetStringUTFChars(env, exit_path, NULL);
    if (!ep)
        return -4;
    snprintf(g_exit_path, sizeof(g_exit_path), "%s", ep);
    (*env)->ReleaseStringUTFChars(env, exit_path, ep);
    unlink(g_exit_path);

    usb_shim_set_fd(usb_fd);

    jsize n = (*env)->GetArrayLength(env, args);
    struct readsb_args *a = calloc(1, sizeof(*a));
    if (!a)
        return -4;
    a->argc = n + 1;
    a->argv = calloc((size_t) n + 2, sizeof(char *));
    if (!a->argv || !(a->argv[0] = strdup("readsb")))
        return -4;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        if (!c)
            return -4;
        a->argv[i + 1] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
        if (!a->argv[i + 1])
            return -4;
    }

    for (int i = 0; i < a->argc; i++)
        fprintf(stderr, "%s%s", i ? " " : "[feeder] starting: ", a->argv[i]);
    fprintf(stderr, "\n");

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024); // readsb expects a desktop-sized main stack
    pthread_t t;
    r = pthread_create(&t, &attr, readsb_thread, a);
    pthread_attr_destroy(&attr);
    if (r != 0)
        return -3;
    pthread_detach(t);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_requestStop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (atomic_load(&g_started))
        readsb_request_exit();
}

JNIEXPORT void JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_setLogcatMirror(JNIEnv *env, jclass clazz, jboolean on) {
    (void) env;
    (void) clazz;
    atomic_store(&g_logcat_mirror, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_setHiddenJsonInterval(JNIEnv *env, jclass clazz, jint seconds) {
    (void) env;
    (void) clazz;
    atomic_store(&g_paused_json_interval_s, seconds > 0 ? seconds : 1);
}

JNIEXPORT void JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_setJsonPaused(JNIEnv *env, jclass clazz, jboolean paused) {
    (void) env;
    (void) clazz;
    atomic_store(&g_json_paused, paused ? 1 : 0);
}

static int is_loopback_port(const struct sockaddr_storage *ss, int port) {
    const struct sockaddr_in *in = (const struct sockaddr_in *) ss;
    return ss->ss_family == AF_INET && in->sin_addr.s_addr == htonl(INADDR_LOOPBACK) &&
           ntohs(in->sin_port) == port;
}

// Whether a TCP connection from 127.0.0.1:peer_port to 127.0.0.1:server_port was made by this
// process, i.e. one of our own sockets has exactly that local and remote end. Loopback ports are
// reachable by every app on the phone, so servers that hand out private data (the gpsd feed
// carries the receiver position) use this to serve readsb only.
JNIEXPORT jboolean JNICALL
Java_com_adsbexchange_feeder_NativeFeeder_isOwnConnection(JNIEnv *env, jclass clazz, jint peer_port,
                                                         jint server_port) {
    (void) env;
    (void) clazz;
    DIR *dir = opendir("/proc/self/fd");
    if (!dir)
        return JNI_FALSE;
    jboolean own = JNI_FALSE;
    struct dirent *e;
    while (!own && (e = readdir(dir)) != NULL) {
        char *end;
        long fd = strtol(e->d_name, &end, 10);
        if (end == e->d_name || *end)
            continue;
        struct sockaddr_storage local, remote;
        socklen_t local_len = sizeof(local), remote_len = sizeof(remote);
        if (getsockname((int) fd, (struct sockaddr *) &local, &local_len) != 0 ||
            getpeername((int) fd, (struct sockaddr *) &remote, &remote_len) != 0)
            continue;
        own = is_loopback_port(&local, peer_port) && is_loopback_port(&remote, server_port);
    }
    closedir(dir);
    return own;
}
