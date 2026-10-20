package com.adsbexchange.feeder

/**
 * readsb, linked into libphonereceiver.so. Only used from the feeder service, which runs in the
 * separate `:feeder` process: readsb can start once per process and ends that process when it
 * exits.
 */
object NativeFeeder {
    init {
        System.loadLibrary("phonereceiver")
    }

    /**
     * Starts readsb on a background thread.
     *
     * @param usbFd file descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
     *   (or -1 for file input); the connection must stay open while readsb runs.
     * @param args readsb command-line arguments, without argv[0].
     * @param logPath readsb's stdout/stderr are written here (and to logcat under tag `readsb` while [setLogcatMirror] is on).
     * @param exitPath readsb's exit code is written here just before the process ends.
     * @return 0 on success, negative if already started or setup failed.
     */
    @JvmStatic external fun start(usbFd: Int, args: Array<String>, logPath: String, exitPath: String): Int

    /** Asks readsb to shut down cleanly; the `:feeder` process exits shortly after. */
    @JvmStatic external fun requestStop()

    /** While nobody is looking at the app, readsb writes aircraft.json less often: see [setHiddenJsonInterval]. */
    @JvmStatic external fun setJsonPaused(paused: Boolean)

    /** How often aircraft.json is written while paused: 60 s (the history), or 5 s for stats uploads. */
    @JvmStatic external fun setHiddenJsonInterval(seconds: Int)

    /** Whether readsb's output is copied to logcat as well as its log file (debug builds only). */
    @JvmStatic external fun setLogcatMirror(on: Boolean)

    /**
     * Whether the loopback connection from [peerPort] to [serverPort] comes from this process.
     * Other apps can reach 127.0.0.1 too; see [com.adsbexchange.feeder.service.GpsdServer].
     */
    @JvmStatic external fun isOwnConnection(peerPort: Int, serverPort: Int): Boolean
}
