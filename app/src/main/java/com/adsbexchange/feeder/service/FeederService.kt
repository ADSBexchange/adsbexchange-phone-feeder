package com.adsbexchange.feeder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.NativeFeeder
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.BatteryGuard
import com.adsbexchange.feeder.data.NetworkGuard
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.writeAtomically
import com.adsbexchange.feeder.data.FeedWatchdog
import com.adsbexchange.feeder.data.ChargeWatchdog
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.HistoryRecorder
import com.adsbexchange.feeder.data.HistorySamples
import com.adsbexchange.feeder.data.HistoryStore
import com.adsbexchange.feeder.data.MlatReadiness
import com.adsbexchange.feeder.data.MlatStatus
import com.adsbexchange.feeder.data.PhoneHealth
import com.adsbexchange.feeder.data.PowerMeter
import com.adsbexchange.feeder.data.PowerTest
import com.adsbexchange.feeder.data.PowerVariant
import com.adsbexchange.feeder.data.RestartBudget
import com.adsbexchange.feeder.data.mlatAltitudeM
import com.adsbexchange.feeder.data.mlatReadiness
import com.adsbexchange.feeder.data.mlatUser
import com.adsbexchange.feeder.mlat.MlatClient
import com.adsbexchange.feeder.mlat.MlatSyncLog
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.FeedLogWatcher
import com.adsbexchange.feeder.data.UiVisibility
import com.adsbexchange.feeder.data.readQuickStats
import com.adsbexchange.feeder.data.RtlSdrDevices
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.data.StatsStatus
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the USB connection and runs readsb. Declared with
 * `android:process=":feeder"` so readsb's process-wide state, and its exit, stay out of the UI
 * process.
 *
 * Returns START_STICKY: if readsb dies unexpectedly the system restarts the service, which
 * reopens the SDR if it is still attached.
 */
class FeederService : Service() {

    private lateinit var paths: FeederPaths
    private val handler = Handler(Looper.getMainLooper())
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var running = false
    private var status = FeederStatus(State.STOPPED)
    private var gpsd: GpsdServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var feedWatcher: FeedLogWatcher? = null
    private var uiVisible = false
    private var screenWasOn = false
    private var cachedSettings: FeederSettings? = null
    private var lastPushedLocation: StationLocation? = null
    private var smokeMode = false
    private var ticks = 0
    private var mlatClient: MlatClient? = null
    private var mlatConfig: MlatClient.Config? = null
    /** The 10-minute MLAT sync line in the log; see [MlatSyncLog]. */
    private var mlatSyncLog = MlatSyncLog()
    private var history: HistoryRecorder? = null
    private var historyStore: HistoryStore? = null
    private var lastAircraftSampleMtime = 0L
    private var statsMtime = 0L
    /** [HistoryRecorder.loopbackBytes] as of the current stats.json; see [recordHistory]. */
    private var loopbackAtStats = 0L
    private var latestMessages: Long? = null
    private val watchdog = FeedWatchdog()
    private val chargeWatchdog = ChargeWatchdog()
    /** Set while feeding is paused to protect the battery; see [BatteryGuard]. */
    private var paused: Reason? = null
    private var latestStartId = 0
    /** When the feed went onto mobile data, while it's there; see [NetworkGuard]. */
    private var mobileDataSinceMs: Long? = null
    private val restartBudget by lazy { RestartBudget(paths.restartsFile) }
    /** The network settings this run uses: [PowerVariant.BASELINE] unless a [PowerTest] picks another. */
    private var variant = PowerVariant.BASELINE
    private var powerTest: PowerTest? = null
    private var powerMeter: PowerMeter? = null
    private var runStartMs = 0L
    private var latestFeedBytes = 0L
    private var latestSamplesDropped = 0L
    /** readsb's Beast output for the MLAT client: a loopback port picked afresh for each run. */
    private var beastPort = 0
    // Debug smoke tests: a local MLAT test server, and the capture's receiver position.
    private var smokeMlatServer: Pair<String, Int>? = null
    private var smokeStatsUrl: String? = null
    private var statsUploader: StatsUploader? = null
    /** Read by the stats upload thread: whether MLAT sends ADS-B Exchange the receiver position. */
    @Volatile private var mlatSendsPosition = false
    /** [StatsUploader.Stats.bytesSent] already added to the history. */
    private var statsBytesCounted = 0L
    private var smokeLocation: StationLocation? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (detached != null && detached.deviceName == device?.deviceName) {
                log("SDR unplugged")
                Notifications.alert(
                    this@FeederService, getString(R.string.alert_unplugged_title),
                    getString(R.string.alert_unplugged_text)
                )
                stopFeeder(Reason.SDR_UNPLUGGED)
            }
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            tick()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    /** While paused: battery changes (level, charging, temperature) may mean it's time to resume. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkResume()
    }

    /**
     * While paused: the default network changing (back to Wi-Fi, say) may mean it's time to
     * resume. Mobile networks report capability changes often, so this waits for them to settle.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = resumeSoon()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = resumeSoon()
        override fun onLost(network: Network) = resumeSoon()
    }
    private val checkResumeRunnable = Runnable { checkResume() }

    private fun resumeSoon() {
        handler.removeCallbacks(checkResumeRunnable)
        handler.postDelayed(checkResumeRunnable, 1_000)
    }

    /** While paused: thermal status has no broadcast, so look now and then too. */
    private val resumeCheck = object : Runnable {
        override fun run() {
            checkResume()
            if (paused != null) handler.postDelayed(this, RESUME_CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        paths = FeederPaths(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FeederSettings.update(this) { it.copy(userStopped = true) }
            BatteryGuard.Hold.clear(paths)
            stopFeeder(Reason.USER_STOPPED)
            return START_NOT_STICKY
        }
        latestStartId = startId
        if (running || paused != null) return START_STICKY

        val hold = BatteryGuard.Hold.read(paths)
        if (intent != null) {
            restartBudget.reset()
        } else if (hold == null && !restartBudget.consumeExpected() && !restartBudget.allow(System.currentTimeMillis())) {
            // Android restarted the service after readsb ended its process, again and again.
            log("readsb exited ${RestartBudget.MAX_EXITS} times in ${RestartBudget.WINDOW_MS / 60_000} minutes; giving up")
            Notifications.alert(this, getString(R.string.alert_sdr_failing_title), getString(R.string.alert_sdr_failing_text))
            publish { FeederStatus(State.STOPPED, Reason.SDR_FAILING) }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val settings = FeederSettings.load(this)
        val smokeFile = intent?.getStringExtra(EXTRA_SMOKE_IFILE)?.takeIf { BuildConfig.DEBUG }
        if (!enterForeground()) {
            // A pause stays a pause: the app resumes it (and the hold) next time it's opened.
            publish { if (hold != null) FeederStatus(State.PAUSED, hold) else FeederStatus(State.STOPPED, Reason.START_FAILED) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val feeding = if (smokeFile != null) intent.hasExtra(EXTRA_SMOKE_FEED) else settings.feedAdsbx
        val reason = holdReason(PhoneHealth.read(this), settings, feeding, hold)
        if (reason != null) {
            if (reason != hold) holdStarted(reason, settings)
            waitToResume(reason)
            return START_STICKY
        }
        if (hold != null) holdEnded(hold)
        publish { FeederStatus(State.STARTING) }
        if (readsbStarted) {
            // A start that arrived while the last run's process is still ending (Start tapped right
            // after Stop, the SDR re-plugged): readsb can't start twice in one process. Let this one
            // end and start in the process Android restarts the service in, as for plannedRestart.
            log("the last run is still shutting down; starting again in a new process")
            restartBudget.expectRestart()
            NativeFeeder.requestStop()
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, STOP_TIMEOUT_MS)
            return START_STICKY
        }
        if (smokeFile != null) {
            startSmokeTest(intent, File(filesDir, smokeFile), settings)
        } else {
            startWithSdr(settings, startId)
        }
        return START_STICKY
    }

    private fun startWithSdr(settings: FeederSettings, startId: Int) {
        val dev = RtlSdrDevices.findAttached(this)
        if (dev == null) {
            log("no SDR attached")
            return fail(Reason.NO_SDR, startId)
        }
        if (!RtlSdrDevices.hasPermission(this, dev)) {
            log("no USB permission for ${RtlSdrDevices.describe(dev)}")
            Notifications.alert(this, getString(R.string.alert_permission_title), getString(R.string.alert_permission_text))
            return fail(Reason.NO_PERMISSION, startId)
        }
        // Never feed under a blank feeder ID. It's only blank while settings.json can't be read and
        // readsb's copy of the ID is gone too (FeederSettings.loadLocked); a later start reads it.
        if (settings.feedAdsbx && settings.uuid.isBlank()) {
            log("not feeding: the feeder ID can't be read right now (settings.json unreadable)")
            return fail(Reason.START_FAILED, startId)
        }
        val conn = getSystemService(UsbManager::class.java).openDevice(dev)
        if (conn == null) {
            log("openDevice failed for ${RtlSdrDevices.describe(dev)}")
            return fail(Reason.START_FAILED, startId)
        }
        connection = conn
        device = dev
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        log("using ${RtlSdrDevices.describe(dev)}")
        val feedTo = if (settings.feedAdsbx) FeederSettings.ADSBX_FEED else null
        startReadsb(conn.fileDescriptor, settings, settings.location, inputFile = null, feedTo = feedTo)
    }

    /**
     * Debug builds only: run readsb on an I/Q file instead of the SDR. The receiver position comes
     * from the gpsd feed, as in a real run. The feed goes to a local test sink if
     * [EXTRA_SMOKE_FEED] is given, and never to ADS-B Exchange.
     */
    private fun startSmokeTest(intent: Intent, file: File, settings: FeederSettings) {
        val location = StationLocation(
            lat = intent.getDoubleExtra(EXTRA_SMOKE_LAT, 0.0),
            lon = intent.getDoubleExtra(EXTRA_SMOKE_LON, 0.0),
            altitudeM = null, accuracyM = null, timeMs = System.currentTimeMillis(),
            source = StationLocation.Source.MANUAL,
        )
        smokeMode = true
        smokeLocation = location
        smokeMlatServer = SmokeTarget.hostPort(intent.getStringExtra(EXTRA_SMOKE_MLAT))
        smokeStatsUrl = SmokeTarget.httpUrl(intent.getStringExtra(EXTRA_SMOKE_STATS))
        val sink = intent.getStringExtra(EXTRA_SMOKE_FEED)?.takeIf { SmokeTarget.hostPort(it, setOf("127.0.0.1")) != null }
        startReadsb(-1, settings.copy(location = null), location, inputFile = file, feedTo = sink)
    }

    private fun startReadsb(
        usbFd: Int, settings: FeederSettings, location: StationLocation?, inputFile: File?, feedTo: String?,
    ) {
        powerTest = PowerTest.read(paths)?.takeIf { it.running }
        variant = powerTest?.current ?: PowerVariant.BASELINE
        runStartMs = System.currentTimeMillis()
        powerMeter = powerTest?.let { PowerMeter(runStartMs) }
        acquireLocks(wakeLock = variant.wakeLock)
        paths.jsonDir.mkdirs()
        // Don't let the UI show the previous run's aircraft while this one starts.
        listOf(paths.aircraftJson, paths.statsJson, paths.receiverJson).forEach { it.delete() }

        val server = GpsdServer(NativeFeeder::isOwnConnection).also { it.start() }
        gpsd = server
        location?.let(server::update)
        lastPushedLocation = location

        // History that exists but can't be read is left alone for this run rather than saved over
        // with zeros (HistoryStore.load); it's picked up again next run.
        val store = HistoryStore(paths) { log(it) }
        try {
            history = HistoryRecorder(store.load())
            historyStore = store
        } catch (e: IOException) {
            log("history: can't be read ($e); not recording history this run, so it isn't written over")
            history = null
            historyStore = null
        }
        paths.uuidFile.writeAtomically(settings.uuid)
        beastPort = freeLoopbackPort()
        val args = settings.readsbArgs(paths, server.port, feedTo, inputFile, beastPort = beastPort)
        feedWatcher = feedTo?.let { FeedLogWatcher(paths.logFile, it) }
        uiVisible = UiVisibility.read(paths)
        // Stats uploads are part of feeding ADS-B Exchange (there's no switch; an old settings file's
        // "statsUploads": false is ignored). Smoke tests send them to a local stand-in only, and never
        // to ADS-B Exchange.
        val statsUrl = when {
            smokeMode -> smokeStatsUrl
            feedTo == FeederSettings.ADSBX_FEED -> StatsUploader.UPLOAD_URL
            else -> null
        }
        NativeFeeder.setJsonPaused(!uiVisible)
        NativeFeeder.setHiddenJsonInterval(if (statsUrl != null) STATS_JSON_INTERVAL_S else HIDDEN_JSON_INTERVAL_S)
        NativeFeeder.setLogcatMirror(BuildConfig.DEBUG)
        readsbStarted = true // even if start fails below: the bridge allows one attempt per process
        val rc = NativeFeeder.start(usbFd, args.toTypedArray(), paths.logFile.absolutePath, paths.exitFile.absolutePath)
        if (rc != 0) {
            log("native start failed ($rc)")
            return stopFeeder(Reason.START_FAILED)
        }
        running = true
        // After readsb has started: its log file starts afresh with it.
        powerTest?.let { log("power test: phase ${it.index + 1} of ${it.plan.size}, ${variant.name.lowercase()}") }
        if (powerTest != null) log("power test: wake lock ${if (variant.wakeLock) "held" else "not held"}" +
            if (variant.mlat) "" else ", MLAT off for the test")
        statsUploader = statsUrl?.let { url ->
            StatsUploader(StatsUploader.Config(settings.uuid, paths.aircraftJson, url), { mlatSendsPosition }) { log(it) }
                .apply { start() }
        }
        publish {
            FeederStatus(
                State.RUNNING, feeding = feedTo != null, feedTarget = feedTo,
                startedMs = System.currentTimeMillis(),
            )
        }
        handler.postDelayed(monitor, TICK_MS)
    }

    /**
     * Every [TICK_MS]: follow the feed connection (so the UI can say how long it has been up and
     * how often it dropped), push a receiver position the user changed while feeding, and
     * refresh the notification every few ticks.
     */
    private fun tick() {
        // Screen-only work (per-second aircraft.json, UI counters) stops while nobody is looking.
        val visible = UiVisibility.read(paths)
        val becameVisible = visible && !uiVisible
        if (visible != uiVisible) {
            uiVisible = visible
            NativeFeeder.setJsonPaused(!visible)
        }

        val feedState = feedWatcher?.poll() ?: FeedState.UNKNOWN
        if (status.feeding && feedState != status.feedState) {
            val dropped = status.feedState == FeedState.CONNECTED && feedState != FeedState.CONNECTED
            publish {
                it.copy(
                    feedState = feedState,
                    connectedSinceMs = if (feedState == FeedState.CONNECTED) System.currentTimeMillis() else null,
                    reconnects = it.reconnects + if (dropped) 1 else 0,
                )
            }
        }

        // Settings only change through the app, so re-read them only while it's on screen.
        val settings = if (visible || cachedSettings == null) FeederSettings.load(this).also { cachedSettings = it }
            else cachedSettings!!
        if (!smokeMode) {
            settings.location?.let { saved ->
                if (saved != lastPushedLocation) {
                    gpsd?.update(saved)
                    lastPushedLocation = saved
                }
            }
        }
        recordHistory(visible, settings)
        watchFeed(settings)
        if (ticks % CHARGE_CHECK_TICKS == 0 && checkBattery(settings)) return
        updateMlat(settings, visible, forcePublish = becameVisible)
        updateStats(visible)

        // Nobody sees the notification with the screen off; refresh it when the screen comes on.
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (screenOn && (!screenWasOn || ticks % NOTIFICATION_EVERY_TICKS == 0)) updateNotification()
        screenWasOn = screenOn
        ticks++
    }

    /**
     * Starts, restarts or stops the MLAT client to match the settings (checked every tick, so
     * e.g. confirming the antenna position in the app starts MLAT within seconds), and publishes
     * its status.
     */
    private fun updateMlat(settings: FeederSettings, uiVisible: Boolean, forcePublish: Boolean) {
        val smoke = smokeMlatServer
        val readiness = when {
            smoke != null -> MlatReadiness.READY
            !status.feeding -> MlatReadiness.NOT_FEEDING
            !variant.mlat -> MlatReadiness.OFF
            else -> settings.mlatReadiness()
        }
        val wanted = if (readiness != MlatReadiness.READY) null else {
            val loc = if (smoke != null) smokeLocation else settings.location
            val alt = if (smoke != null) SMOKE_ALTITUDE_M else settings.mlatAltitudeM()
            if (loc == null || alt == null) null else MlatClient.Config(
                uuid = settings.uuid, user = settings.mlatUser(), lat = loc.lat, lon = loc.lon, altM = alt,
                privacy = settings.mlatPrivacy, server = smoke, inputPort = beastPort,
            )
        }
        if (wanted != mlatConfig) {
            mlatClient?.stop()
            mlatClient = wanted?.let { MlatClient(it, NativeFeeder::isOwnConnection) { msg -> log(msg) }.apply { start() } }
            // Smoke tests (MLAT against the stand-in server) log every 5 s so a short run shows a line.
            mlatSyncLog = MlatSyncLog(if (smoke != null) 5_000L else MlatSyncLog.EVERY_MS).apply { restart(System.currentTimeMillis()) }
            mlatConfig = wanted
            mlatSendsPosition = wanted != null
        }
        val s = mlatClient?.stats
        s?.let { stats -> mlatSyncLog.next(stats, System.currentTimeMillis())?.let { log(it) } }
        val next = MlatStatus(
            readiness = if (wanted == null && readiness == MlatReadiness.READY) MlatReadiness.NO_LOCATION else readiness,
            state = s?.state?.name, server = s?.server, connectedSinceMs = s?.connectedSinceMs,
            peerCount = s?.peerCount, outlierPercent = s?.outlierPercent, badSyncTimeout = s?.badSyncTimeout,
            requested = s?.requested ?: 0, adsbAircraft = s?.adsbAircraft ?: 0, modesAircraft = s?.modesAircraft ?: 0,
            syncsSent = s?.syncsSent ?: 0, mlatSent = s?.mlatSent ?: 0, bytesSent = s?.bytesSent ?: 0,
            loopbackBytes = loopbackAtStats, message = s?.message,
        )
        val prev = status.mlat
        val stateChanged = prev?.readiness != next.readiness || prev.state != next.state ||
            prev.connectedSinceMs != next.connectedSinceMs || prev.message != next.message
        // Counters are only for the screen: while nobody looks, write state changes only.
        if (stateChanged || forcePublish || (uiVisible && ticks % MLAT_COUNTERS_EVERY_TICKS == 0)) publish { it.copy(mlat = next) }
    }

    /**
     * Stats uploads ([StatsUploader]): what they've sent goes into the history, and how they're
     * going into the status. As for MLAT, while nobody looks only changes of state are published.
     */
    private fun updateStats(visible: Boolean) {
        val s = statsUploader?.stats ?: return
        val contributing = status.feeding && status.feedState == FeedState.CONNECTED
        history?.onStatsUploaded(s.bytesSent - statsBytesCounted, contributing, System.currentTimeMillis())
        statsBytesCounted = s.bytesSent
        val next = StatsStatus(s.uploads, s.bytesSent, s.lastOkMs, s.error)
        val prev = status.stats
        val stateChanged = prev == null || prev.error != next.error || (prev.lastOkMs == null) != (next.lastOkMs == null)
        if (stateChanged || (visible && ticks % MLAT_COUNTERS_EVERY_TICKS == 0 && next != prev)) publish { it.copy(stats = next) }
    }

    /**
     * Lifetime history (see [HistoryRecorder]). Feeding time and MLAT counters every tick; readsb's
     * totals and an aircraft snapshot every 10 s while the app is on screen, every minute otherwise
     * (readsb then writes aircraft.json only once a minute anyway). Saved every 10 s while on
     * screen, so the History tab is current, and every 5 minutes otherwise.
     */
    private fun recordHistory(visible: Boolean, settings: FeederSettings) {
        val h = history ?: return
        val now = System.currentTimeMillis()
        // The History tab's Reset button: the UI can't write the history, so it asks through settings.
        settings.rangeResetMs?.let { if (it > (h.rangeStartedMs ?: 0)) h.resetRange(it) }
        val contributing = status.feeding && status.feedState == FeedState.CONNECTED
        h.onFeedTick(contributing, status.connectedSinceMs, now)
        mlatClient?.let { c ->
            val s = c.stats
            h.onMlat(c, s.syncsSent, s.mlatSent, s.bytesSent, s.inputBytes, contributing, now)
        }
        // readsb rewrites stats.json every 10 s. Note the loopback count when it does, so bytes_out
        // minus loopback compares the same moment (within a tick), for the history and the Live feed.
        val m = paths.statsJson.lastModified()
        if (m != statsMtime) {
            statsMtime = m
            loopbackAtStats = h.loopbackBytes
        }
        if (ticks % (if (visible) HISTORY_SAMPLE_VISIBLE_TICKS else HISTORY_SAMPLE_HIDDEN_TICKS) == 0) {
            HistorySamples.stats(paths)?.let {
                latestMessages = it.messages
                latestFeedBytes = (it.bytesOut - loopbackAtStats).coerceAtLeast(0)
                latestSamplesDropped = it.samplesDropped
                h.onStats(it.session, it.messages, it.bytesOut, it.maxDistanceM, loopbackAtStats, contributing, now)
            }
            val mtime = paths.aircraftJson.lastModified()
            if (mtime != lastAircraftSampleMtime) {
                lastAircraftSampleMtime = mtime
                HistorySamples.aircraft(paths)?.let {
                    h.onAircraft(it.addresses, it.positions, recordRange = true, contributing, now)
                }
            }
        }
        if (ticks % (if (visible) HISTORY_SAVE_VISIBLE_TICKS else HISTORY_SAVE_HIDDEN_TICKS) == 0) saveHistory()
    }

    /** "Feeding stopped" alerts; see [FeedWatchdog]. */
    private fun watchFeed(settings: FeederSettings) {
        val action = if (!settings.feedAlerts) watchdog.clear() else watchdog.tick(
            running = running, feeding = status.feeding, feedConnected = status.feedState == FeedState.CONNECTED,
            messagesTotal = latestMessages, nowMs = System.currentTimeMillis(),
        )
        when (action) {
            is FeedWatchdog.Action.Alert -> {
                val (title, text) = when (action.problem) {
                    FeedWatchdog.Problem.FEED_DOWN -> R.string.alert_feed_down_title to R.string.alert_feed_down_text
                    FeedWatchdog.Problem.NO_AIRCRAFT -> R.string.alert_no_aircraft_title to R.string.alert_no_aircraft_text
                }
                log("alert: ${action.problem}")
                Notifications.alert(this, getString(title), getString(text), Notifications.ID_FEED_PROBLEM)
            }
            FeedWatchdog.Action.Clear -> Notifications.clearAlert(this, Notifications.ID_FEED_PROBLEM)
            null -> Unit
        }
    }

    /**
     * Every [CHARGE_CHECK_TICKS]: pauses feeding if the battery needs it, or if it has been on
     * mobile data for [NetworkGuard.PAUSE_AFTER_MS] (returns true then), records the power drawn
     * on battery for the History tab, and watches the charger.
     */
    private fun checkBattery(settings: FeederSettings): Boolean {
        val h = PhoneHealth.read(this)
        BatteryGuard.hold(h)?.let {
            pause(it, settings)
            return true
        }
        val now = System.currentTimeMillis()
        if (NetworkGuard.wouldUseMobileData(defaultNetworkMetered(), status.feeding, settings)) {
            val since = mobileDataSinceMs ?: now.also {
                mobileDataSinceMs = it
                log("on mobile data: feeding pauses in ${NetworkGuard.PAUSE_AFTER_MS / 1000} s unless Wi-Fi returns")
            }
            if (now - since >= NetworkGuard.PAUSE_AFTER_MS) {
                pause(Reason.MOBILE_DATA, settings)
                return true
            }
        } else {
            mobileDataSinceMs = null
        }
        val contributing = status.feeding && status.feedState == FeedState.CONNECTED
        history?.onBattery(h.batteryPowerW?.let { -it }?.takeIf { !h.onCharger }, contributing, System.currentTimeMillis())
        watchCharge(settings, h)
        return powerTest != null && stepPowerTest(h, contributing)
    }

    /** "Battery running down on the charger" alert; see [ChargeWatchdog]. */
    private fun watchCharge(settings: FeederSettings, h: PhoneHealth) {
        val change = if (!settings.feedAlerts) chargeWatchdog.clear() else chargeWatchdog.tick(
            running = running,
            onCharger = h.onCharger,
            charging = h.batteryCharging,
            levelPercent = h.batteryPercent,
        )
        when (change) {
            ChargeWatchdog.Change.ALERT -> {
                log("alert: battery running down on the charger (${h.batteryPercent}%)")
                Notifications.alert(this, getString(R.string.alert_charge_title), getString(R.string.alert_charge_text),
                    Notifications.ID_CHARGE_PROBLEM)
            }
            ChargeWatchdog.Change.CLEAR -> Notifications.clearAlert(this, Notifications.ID_CHARGE_PROBLEM)
            null -> Unit
        }
        if (change != null) publish { it.copy(chargeProblem = chargeWatchdog.alerted) }
    }

    private fun saveHistory() {
        val store = historyStore ?: return
        runCatching { history?.save(store, System.currentTimeMillis()) }.onFailure { log("history: not saved: $it") }
    }

    /**
     * Stops readsb to protect the battery. readsb can't start again within this process, so the
     * process ends with it while the service is still started: Android then restarts the service
     * in a new process, which finds the hold and waits there (see [onStartCommand]).
     */
    private fun pause(reason: Reason, settings: FeederSettings) {
        holdStarted(reason, settings)
        endPowerTest("feeding paused (${reason.name.lowercase()})")
        paused = reason // a start arriving before the process ends waits for the restart instead
        running = false
        handler.removeCallbacks(monitor)
        saveHistory()
        mlatClient?.stop()
        statsUploader?.stop()
        if (watchdog.clear() != null) Notifications.clearAlert(this, Notifications.ID_FEED_PROBLEM)
        if (chargeWatchdog.clear() != null) Notifications.clearAlert(this, Notifications.ID_CHARGE_PROBLEM)
        publish { FeederStatus(State.PAUSED, reason) }
        showPausedNotification(reason)
        NativeFeeder.requestStop()
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, STOP_TIMEOUT_MS)
    }

    private fun holdStarted(reason: Reason, settings: FeederSettings) {
        val h = PhoneHealth.read(this)
        log("pausing: ${reason.name.lowercase()} (battery ${h.batteryPercent}%, ${h.batteryTempC} °C, thermal ${h.thermal}, charging ${h.batteryCharging})")
        BatteryGuard.Hold.write(paths, reason)
        if (settings.feedAlerts) {
            val (title, text) = when (reason) {
                Reason.HOT -> R.string.alert_hot_title to R.string.alert_hot_text
                Reason.MOBILE_DATA -> R.string.alert_mobile_data_title to R.string.alert_mobile_data_text
                else -> R.string.alert_low_battery_title to R.string.alert_low_battery_text
            }
            Notifications.alert(this, getString(title), getString(text), Notifications.ID_PAUSED)
        }
    }

    private fun holdEnded(reason: Reason) {
        log("resuming after the ${reason.name.lowercase()} pause")
        BatteryGuard.Hold.clear(paths)
        Notifications.clearAlert(this, Notifications.ID_PAUSED)
    }

    /** Why feeding should be paused now, if it should; [current] is the pause in force, if any. */
    private fun holdReason(h: PhoneHealth, settings: FeederSettings, feeding: Boolean, current: Reason?): Reason? =
        BatteryGuard.hold(h, current)
            ?: Reason.MOBILE_DATA.takeIf { NetworkGuard.wouldUseMobileData(defaultNetworkMetered(), feeding, settings) }

    /** Whether the default network is metered (mobile data, a metered hotspot); null with no network. */
    private fun defaultNetworkMetered(): Boolean? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Paused, in the foreground but without readsb, the SDR or wake locks, until [checkResume]
     * says go: on battery changes, network changes, and once a minute.
     */
    private fun waitToResume(reason: Reason) {
        paused = reason
        publish { FeederStatus(State.PAUSED, reason) }
        showPausedNotification(reason)
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        runCatching { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback) }
        handler.postDelayed(resumeCheck, RESUME_CHECK_MS)
    }

    private fun checkResume() {
        val current = paused ?: return
        val settings = FeederSettings.load(this)
        val next = holdReason(PhoneHealth.read(this), settings, settings.feedAdsbx, current)
        if (next == current) return
        if (next != null) {
            holdStarted(next, settings)
            paused = next
            publish { FeederStatus(State.PAUSED, next) }
            showPausedNotification(next)
            return
        }
        stopWaiting()
        holdEnded(current)
        publish { FeederStatus(State.STARTING) }
        updateNotification()
        startWithSdr(settings, latestStartId)
    }

    private fun stopWaiting() {
        if (paused == null || running) return
        paused = null
        handler.removeCallbacks(resumeCheck)
        handler.removeCallbacks(checkResumeRunnable)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    private fun showPausedNotification(reason: Reason) {
        val text = getString(when (reason) {
            Reason.HOT -> R.string.notif_paused_hot
            Reason.MOBILE_DATA -> R.string.notif_paused_mobile_data
            else -> R.string.notif_paused_low_battery
        })
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(Notifications.ID_FEEDER, Notifications.feeder(this, text))
        }
    }

    /**
     * Every battery check while a [PowerTest] runs: adds the reading to the current phase, saves
     * now and then, and when the phase has what it needs moves to the next variant, which takes a
     * readsb restart (returns true then: this process is ending).
     */
    private fun stepPowerTest(h: PhoneHealth, feeding: Boolean): Boolean {
        val test = powerTest ?: return false
        val meter = powerMeter ?: return false
        val now = System.currentTimeMillis()
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        val phase = meter.sample(
            test.phases.last(), now, h.chargeCounterUah, h.voltageMv, h.batteryPowerW?.let { -it },
            counts = feeding && !screenOn, onBattery = !h.onCharger,
        ).let {
            it.copy(
                runMs = now - runStartMs, messages = latestMessages ?: 0, feedBytes = latestFeedBytes,
                reconnects = status.reconnects, samplesDropped = latestSamplesDropped,
                maxBadSync = maxOf(it.maxBadSync, status.mlat?.badSyncTimeout ?: 0),
            )
        }
        var next = test.withCurrent(phase)
        val done = next.phaseDone(now, runStartMs)
        if (done) next = next.next(now)
        // The app owns starting and cancelling: if it cancelled meanwhile, go back to normal.
        val onDisk = PowerTest.read(paths)
        if (onDisk == null || onDisk.startedMs != test.startedMs || !onDisk.running) {
            log("power test: cancelled")
            powerTest = null
            if (variant != PowerVariant.BASELINE) plannedRestart()
            return variant != PowerVariant.BASELINE
        }
        if (done || ticks % (CHARGE_CHECK_TICKS * POWER_TEST_SAVE_CHECKS) == 0) runCatching { PowerTest.write(paths, next) }
        powerTest = next
        if (!done) return false
        log(("power test: ${variant.name.lowercase()} done, %d min counted (%d on battery): %.2f W (counter), " +
            "%.2f W (sampled), %d stalls, %d samples dropped, %d feed drops, worst bad-sync %d")
            .format(Locale.US, phase.countedMs / 60_000, phase.batteryMs / 60_000, phase.counterW ?: Double.NaN,
                phase.sampledW ?: Double.NaN, phase.stalls, phase.samplesDropped, phase.reconnects, phase.maxBadSync))
        if (!next.running) {
            powerTest = null
            Notifications.alert(this, getString(R.string.alert_power_test_title), getString(R.string.alert_power_test_text),
                Notifications.ID_POWER_TEST)
        }
        val wanted = next.current ?: PowerVariant.BASELINE
        if (wanted == variant) {
            runStartMs = now
            powerMeter = PowerMeter(now)
            return false
        }
        plannedRestart()
        return true
    }

    /** Ends a running [PowerTest] early, keeping what it measured; [why] is shown with the results. */
    private fun endPowerTest(why: String) {
        val test = powerTest ?: return
        powerTest = null
        val onDisk = PowerTest.read(paths) ?: return
        if (onDisk.startedMs == test.startedMs && onDisk.running) {
            log("power test: stopped, $why")
            runCatching { PowerTest.write(paths, onDisk.stop(System.currentTimeMillis(), why)) }
        }
    }

    /**
     * Restarts readsb with new settings. It can't restart within this process, so the process ends
     * with the service still started and Android starts it again, as for [pause]; the restart is
     * announced so it doesn't count towards the [RestartBudget].
     */
    private fun plannedRestart() {
        restartBudget.expectRestart()
        running = false
        handler.removeCallbacks(monitor)
        saveHistory()
        mlatClient?.stop()
        statsUploader?.stop()
        publish { FeederStatus(State.STARTING) }
        NativeFeeder.requestStop()
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, STOP_TIMEOUT_MS)
    }

    private fun fail(reason: Reason, startId: Int) {
        publish { FeederStatus(State.STOPPED, reason) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // stopSelf(startId), not stopSelf(): a newer start (user tapped Start, SDR re-plugged)
        // that arrived meanwhile must not be cancelled by this older, failed one.
        stopSelf(startId)
    }

    private fun stopFeeder(reason: Reason) {
        stopWaiting()
        endPowerTest("feeding stopped (${reason.name.lowercase()})")
        saveHistory()
        // Stopped on purpose (or unplugged, which has its own alert): nothing left to warn about.
        if (watchdog.clear() != null) Notifications.clearAlert(this, Notifications.ID_FEED_PROBLEM)
        if (chargeWatchdog.clear() != null) Notifications.clearAlert(this, Notifications.ID_CHARGE_PROBLEM)
        publish { it.copy(state = State.STOPPED, reason = reason, feedState = FeedState.UNKNOWN, connectedSinceMs = null) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopWaiting()
        handler.removeCallbacksAndMessages(null)
        saveHistory()
        mlatClient?.stop()
        statsUploader?.stop()
        if (device != null) runCatching { unregisterReceiver(detachReceiver) }
        releaseLocks()
        if (running) {
            // readsb shuts down and then ends this process. The USB connection stays open until
            // then because readsb is still using it. Kill the process ourselves if it hangs.
            NativeFeeder.requestStop()
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, STOP_TIMEOUT_MS)
        }
        gpsd?.close()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notifications.ID_FEEDER,
            Notifications.feeder(this, getString(R.string.notif_starting)), type)
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when restarted from the background on
        // Android 12+.
        log("cannot enter foreground: $e")
        false
    }

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    private fun updateNotification() {
        val q = readQuickStats(paths)
        val text = when (q.messagesPerSecond) {
            null -> getString(R.string.notif_starting)
            else -> getString(
                if (status.feeding) R.string.notif_feeding else R.string.notif_receiving,
                "%.0f".format(Locale.US, q.messagesPerSecond),
                q.aircraft ?: 0,
            )
        }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(Notifications.ID_FEEDER, Notifications.feeder(this, text))
        }
    }

    /** Updates [status] and writes it to the status file the UI process reads. */
    private fun publish(change: (FeederStatus) -> FeederStatus) {
        status = change(status).copy(updatedMs = System.currentTimeMillis())
        runCatching { status.write(paths.statusFile) }
    }

    @SuppressLint("WakelockTimeout") // held for as long as the feeder runs, by design
    private fun acquireLocks(wakeLock: Boolean) {
        // Off only for the power test's NO_WAKE_LOCK phase, which finds out whether the feeder needs it.
        // (There's no Wi-Fi lock: measured, it made no difference on a Pixel 7 Pro, and the feed
        // didn't drop without it.)
        if (!wakeLock) return
        this.wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdsbxFeeder:feeder")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun log(message: String) = appendLog(this, message)

    /** A loopback port that's free now: the system picks one, and it's released for readsb to bind. */
    // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket - loopback, opened and closed at once, no traffic
    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    companion object {
        /**
         * Whether readsb has been started in this process. It outlives a service instance: after
         * Stop, the process lives on for a few seconds while readsb shuts down, and a new start can
         * arrive in it.
         */
        @Volatile private var readsbStarted = false

        const val ACTION_STOP = "com.adsbexchange.feeder.action.STOP"
        // Debug-build smoke test: file name relative to filesDir, the receiver position, and an
        // optional local feed sink ("127.0.0.1,port") so the feed path can be tested offline.
        const val EXTRA_SMOKE_IFILE = "smoke_ifile"
        const val EXTRA_SMOKE_LAT = "smoke_lat"
        const val EXTRA_SMOKE_LON = "smoke_lon"
        const val EXTRA_SMOKE_FEED = "smoke_feed"
        /** Debug builds: MLAT test server as "host,port" (10.0.2.2 = the emulator host, or 127.0.0.1). */
        const val EXTRA_SMOKE_MLAT = "smoke_mlat"
        private const val SMOKE_ALTITUDE_M = 100.0
        /** Debug builds: where to send stats uploads, "http://10.0.2.2:port/..." or 127.0.0.1 only. */
        const val EXTRA_SMOKE_STATS = "smoke_stats"
        /** aircraft.json while nobody looks: every 5 s for stats uploads, else once a minute (history). */
        private const val STATS_JSON_INTERVAL_S = 5
        private const val HIDDEN_JSON_INTERVAL_S = 60
        private const val MLAT_COUNTERS_EVERY_TICKS = 2
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TICK_MS = 2_000L
        private const val NOTIFICATION_EVERY_TICKS = 8
        private const val HISTORY_SAMPLE_VISIBLE_TICKS = 5 // 10 s
        private const val CHARGE_CHECK_TICKS = 5 // 10 s: a few cheap battery and thermal reads
        private const val RESUME_CHECK_MS = 60_000L
        private const val POWER_TEST_SAVE_CHECKS = 6 // every minute
        private const val HISTORY_SAMPLE_HIDDEN_TICKS = 30 // 1 min
        private const val HISTORY_SAVE_VISIBLE_TICKS = 5 // 10 s
        private const val HISTORY_SAVE_HIDDEN_TICKS = 150 // 5 min

        fun start(context: Context, extras: Bundle? = null) {
            val intent = Intent(context, FeederService::class.java)
            extras?.let { intent.putExtras(it) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FeederService::class.java).setAction(ACTION_STOP))
        }

        /** Appends a `[feeder]` line to readsb.log so it shows in the app's log view. */
        fun appendLog(context: Context, message: String) {
            Log.i("FeederService", message)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            runCatching {
                FeederPaths(context).apply { root.mkdirs() }.logFile.appendText("[$time] [feeder] $message\n")
            }
        }
    }
}
