// Inserted into the Android copy of readsb.c just before readsb_main() (see CMakeLists.txt),
// so it can reach readsb.c's static functions.

// Replaces upstream configureSignals(), which ignores signals 1-31 for the whole process.
// Only hook the shutdown signals and leave everything else to ART.
static void android_configure_signals(void) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGINT, exitHandler);
    signal(SIGTERM, exitHandler);
    signal(SIGHUP, exitHandler);
}

// Ask readsb to shut down cleanly, exactly as if it had received SIGTERM.
void readsb_request_exit(void) {
    exitHandler(SIGTERM);
}
