package com.adsbexchange.feeder.ui

import android.app.ActivityManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.History
import com.adsbexchange.feeder.data.HistoryRecorder
import com.adsbexchange.feeder.data.MlatStatus
import com.adsbexchange.feeder.data.mlatReadiness
import com.adsbexchange.feeder.data.PhoneHealth
import com.adsbexchange.feeder.data.PowerTest
import com.adsbexchange.feeder.data.RadarTrails
import com.adsbexchange.feeder.data.ReceiverStatus
import com.adsbexchange.feeder.data.ReceiverStatusReader
import com.adsbexchange.feeder.data.RtlSdrDevices
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.data.StationLocator
import com.adsbexchange.feeder.data.StatsStatus
import com.adsbexchange.feeder.data.TypedNumber
import com.adsbexchange.feeder.data.UiVisibility
import com.adsbexchange.feeder.service.FeederService
import com.adsbexchange.feeder.service.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed interface SdrState {
    /** The phone has no USB host support, so no SDR can ever work. */
    data object NoUsbHost : SdrState
    data object NotConnected : SdrState
    data class NeedsPermission(val name: String) : SdrState
    data class Ready(val name: String) : SdrState
}

sealed interface RunState {
    data class Stopped(val reason: FeederStatus.Reason) : RunState
    data object Starting : RunState
    data object Running : RunState
    /** Paused to protect the battery ([FeederStatus.Reason.HOT] or [FeederStatus.Reason.LOW_BATTERY]); resumes on its own. */
    data class Paused(val reason: FeederStatus.Reason) : RunState
    /** readsb ended on its own. [error] is its last error-looking log line, if any. */
    data class Failed(val exitCode: Int?, val error: String?) : RunState
}

data class LocatingProgress(val bestAccuracyM: Float?, val fixes: Int)

data class UiState(
    val settings: FeederSettings,
    val sdr: SdrState = SdrState.NotConnected,
    val run: RunState = RunState.Stopped(FeederStatus.Reason.NONE),
    val receiver: ReceiverStatus? = null,
    val health: PhoneHealth? = null,
    val locationPermission: Boolean = false,
    val locating: LocatingProgress? = null,
    /** Whether the current run sends to ADS-B Exchange (reported by the service, not the setting). */
    val feedingAdsbx: Boolean = false,
    val feed: FeedStats = FeedStats(),
    /** MLAT: live client status from the service while feeding, else just readiness from settings. */
    val mlat: MlatStatus? = null,
    /** Lifetime contribution (History tab); null until this phone has fed. */
    val history: History? = null,
    /** The battery is running down although the phone has been on a charger (from the feeder service). */
    val chargeProblem: Boolean = false,
    /** Stats uploads, from the feeder service while it runs. */
    val stats: StatsStatus? = null,
    /** Where each aircraft has been over the last few minutes, by hex: the radar's trails. */
    val trails: Map<String, List<RadarTrails.Point>> = emptyMap(),
    /** The antenna coach, while it's open. */
    val coach: AntennaCoach.State? = null,
    /** The power tests progress or results, if one has been run (see [PowerTest]). */
    val powerTest: PowerTest? = null,
    /** Developer options shown: unlocked by tapping the version number. */
    val developer: Boolean = false,
    /** One-off message for a snackbar; cleared by [MainViewModel.messageShown]. */
    val message: String? = null,
) {
    val feeding get() = run == RunState.Running
}

/** Live numbers for the feed card: the proof that this phone is actually sending. */
data class FeedStats(
    val state: FeedState = FeedState.UNKNOWN,
    val connectedSinceMs: Long? = null,
    val reconnects: Int = 0,
    val startedMs: Long? = null,
    /** Bytes the feed has sent this run (readsb's bytes_out minus what our MLAT client read on loopback). */
    val bytesSent: Long? = null,
    val uploadBytesPerSec: Double? = null,
    val messagesTotal: Long? = null,
    val aircraftSeen: Long? = null,
    /** Messages per second, one sample a second, oldest first; up to [MainViewModel.HISTORY_SECONDS]. */
    val rateHistory: List<Float> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val paths = FeederPaths(app)
    private val reader = ReceiverStatusReader(paths)
    private val locator = StationLocator(app)

    private val _state = MutableStateFlow(UiState(FeederSettings.load(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var locateJob: Job? = null
    private var pollJob: Job? = null
    private var visibleSinceMs = 0L
    private var hiddenSinceMs = 0L
    private var lastAutoStartMs = 0L
    private var permissionAskedFor: String? = null
    private var lastMoveCheckMs = 0L
    private var lastHistoryMtime = -1L
    private val coach = AntennaCoach()
    private var coachOpen = false
    @Volatile private var lastHistory: History? = null
    private var lastHealth: PhoneHealth? = null
    private var lastHealthMs = 0L

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                refreshNow()
                maybeAutoStart(force = true)
            } else {
                showMessage(context.getString(R.string.msg_usb_denied))
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        UiVisibility.write(paths, false)
        runCatching { context.unregisterReceiver(usbPermissionReceiver) }
    }

    // --- actions -----------------------------------------------------------------------------

    fun finishOnboarding(name: String, antennaConfirmed: Boolean = false) {
        saveSettings {
            it.copy(
                name = FeederSettings.sanitizeName(name), onboardingDone = true,
                antennaConfirmed = it.antennaConfirmed || antennaConfirmed,
            )
        }
        onAppVisible()
    }

    /** Called on every resume: start refreshing, plug-and-play checks, "has the phone moved?". */
    fun onAppVisible() {
        val now = System.currentTimeMillis()
        // The chart only fills while visible; after a longer absence start it afresh.
        if (hiddenSinceMs != 0L && now - hiddenSinceMs > HISTORY_SECONDS * 1000L) rateHistory.clear()
        // Trails only fill while visible too: after a gap they'd jump across it.
        if (hiddenSinceMs != 0L && now - hiddenSinceMs > RadarTrails.EVERY_MS * 3) trails.clear()
        visibleSinceMs = now
        UiVisibility.write(paths, true)
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                var lastMark = System.currentTimeMillis()
                while (isActive) {
                    refresh()
                    if (System.currentTimeMillis() - lastMark > UiVisibility.REFRESH_MS) {
                        lastMark = System.currentTimeMillis()
                        withContext(Dispatchers.IO) { UiVisibility.write(paths, true) }
                    }
                    delay(POLL_MS)
                }
            }
        }
        ensureLocation()
        maybeAutoStart(force = false)
    }

    /** Called on pause: nothing on screen needs refreshing, and the feeder can skip screen-only work. */
    fun onAppHidden() {
        pollJob?.cancel()
        pollJob = null
        hiddenSinceMs = System.currentTimeMillis()
        UiVisibility.write(paths, false)
    }

    /** The system launched us because an SDR was plugged in, and granted USB permission with it. */
    fun onSdrAttached() {
        saveSettings { it.copy(userStopped = false) }
        Notifications.clearAlert(context)
        refreshNow()
        maybeAutoStart(force = true)
    }

    fun start() {
        saveSettings { it.copy(userStopped = false) }
        when (val sdr = currentSdrState()) {
            is SdrState.Ready -> startService()
            is SdrState.NeedsPermission -> requestUsbPermission()
            else -> showMessage(context.getString(R.string.msg_plug_in_first))
        }
        refreshNow()
    }

    fun stop() {
        saveSettings { it.copy(userStopped = true) }
        FeederService.stop(context)
    }

    fun requestUsbPermission() {
        val device = RtlSdrDevices.findAttached(context) ?: return
        permissionAskedFor = device.deviceName
        // Mutable so the system can attach the result extras; explicit (package set) because
        // Android 14+ rejects mutable PendingIntents with implicit intents.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        context.getSystemService(UsbManager::class.java)?.requestPermission(device, pi)
    }

    /** Blank is allowed: the name is optional (MLAT then uses an anonymous id). */
    fun rename(name: String) {
        saveSettings { it.copy(name = FeederSettings.sanitizeName(name)) }
        showMessage(context.getString(R.string.msg_name_saved))
    }

    fun setMlatEnabled(on: Boolean) = saveSettings { it.copy(mlatEnabled = on) }

    fun setMlatPrivacy(on: Boolean) = saveSettings { it.copy(mlatPrivacy = on) }

    fun setAntennaConfirmed(on: Boolean) = saveSettings { it.copy(antennaConfirmed = on) }

    /** Blank clears the override, so the GPS altitude is used again. */
    fun setAntennaAltitude(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            saveSettings { it.copy(antennaAltitudeM = null) }
            return showMessage(context.getString(R.string.msg_saved))
        }
        val v = TypedNumber.metres(t)
        if (v == null || v !in -420.0..5100.0) return showMessage(context.getString(R.string.msg_altitude_invalid))
        saveSettings { it.copy(antennaAltitudeM = v) }
        showMessage(context.getString(R.string.msg_saved))
    }

    fun setStatsUploads(on: Boolean) {
        saveSettings { it.copy(statsUploads = on) }
        if (_state.value.feeding) showMessage(context.getString(R.string.msg_restart_to_apply))
    }

    /** [nm] 0 for Auto. Only the radar reads it, so nothing needs restarting. */
    fun setRadarRange(nm: Int) = saveSettings { it.copy(radarRangeNm = nm) }

    fun setFeedOverMobileData(on: Boolean) {
        saveSettings { it.copy(feedOverMobileData = on) }
        resumeIfMobileDataAllowed()
    }

    /** Paused for mobile data, and a setting now allows it: start again now rather than at the next check. */
    private fun resumeIfMobileDataAllowed() {
        val s = _state.value
        if ((s.run as? RunState.Paused)?.reason != FeederStatus.Reason.MOBILE_DATA) return
        if (s.settings.feedOverMobileData || !s.settings.feedAdsbx) restart()
    }

    fun setAutoStart(on: Boolean) = saveSettings { it.copy(autoStart = on) }

    fun setFeedAlerts(on: Boolean) = saveSettings { it.copy(feedAlerts = on) }

    /** readsb reads it only at start, as the gain. */
    fun setBiasTee(on: Boolean) {
        saveSettings { it.copy(biasTee = on) }
        showMessage(context.getString(if (_state.value.feeding) R.string.msg_restart_to_apply else R.string.msg_saved))
    }

    fun setRadarAirports(on: Boolean) = saveSettings { it.copy(radarAirports = on) }

    /** Asks the feeder service to start the range plot again (it owns the history). */
    fun resetRange() = saveSettings { it.copy(rangeResetMs = System.currentTimeMillis()) }

    // --- antenna coach: fed from refresh(), one sample a second while it's open -------------

    fun openCoach() {
        coachOpen = true
        _state.update { it.copy(coach = coach.state()) }
    }

    fun closeCoach() {
        coachOpen = false
        coach.cancel()
        _state.update { it.copy(coach = null) }
    }

    fun measureSpot(label: String) = coachChange { coach.measure(label) }
    fun cancelMeasurement() = coachChange { coach.cancel() }
    fun removeSpot(spot: AntennaCoach.Spot) = coachChange { coach.remove(spot) }

    private fun coachChange(change: () -> Unit) {
        change()
        if (coachOpen) _state.update { it.copy(coach = coach.state()) }
    }

    fun setFeedAdsbx(on: Boolean) {
        saveSettings { it.copy(feedAdsbx = on) }
        if (_state.value.feeding) showMessage(context.getString(R.string.msg_restart_to_apply))
        resumeIfMobileDataAllowed()
    }

    fun setGain(gain: String) {
        // Saved as readsb reads it: "auto" or a plain number ("49,6" typed is saved as "49.6").
        val g = gain.trim().lowercase().let { t ->
            if (t == "auto") t else TypedNumber.decibels(t)?.takeIf { it in 0.0..60.0 }
                ?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        } ?: return showMessage(context.getString(R.string.msg_gain_invalid))
        saveSettings { it.copy(gain = g) }
        showMessage(context.getString(if (_state.value.feeding) R.string.msg_restart_to_apply else R.string.msg_saved))
    }

    fun setManualLocation(lat: Double, lon: Double) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return showMessage(context.getString(R.string.msg_location_invalid))
        saveSettings {
            it.copy(location = StationLocation(lat, lon, null, null, System.currentTimeMillis(), StationLocation.Source.MANUAL))
        }
        showMessage(context.getString(R.string.msg_location_saved))
    }

    /** Take a fresh, careful GPS fix now (user tapped "Update from GPS"). */
    fun updateLocationFromGps() = acquireLocation(announce = true)

    fun onLocationPermissionResult() {
        refreshNow()
        ensureLocation()
    }

    fun unlockDeveloper() {
        if (_state.value.developer) return
        _state.update { it.copy(developer = true) }
        showMessage(context.getString(R.string.msg_developer_unlocked))
    }

    /**
     * Starts a [PowerTest]: feeding restarts with its first variant, and the service takes it from
     * there. On a charger or on battery; on battery it needs enough charge to last the hour.
     */
    fun startPowerTest() {
        val h = PhoneHealth.read(context)
        when {
            _state.value.run != RunState.Running -> showMessage(context.getString(R.string.msg_power_test_feed_first))
            !h.onCharger && (h.batteryPercent ?: 0) < PowerTest.MIN_START_PERCENT ->
                showMessage(context.getString(R.string.msg_power_test_charge, PowerTest.MIN_START_PERCENT))
            else -> {
                FeederService.stop(context)
                viewModelScope.launch {
                    delay(3_000)
                    PowerTest.write(paths, PowerTest.start(System.currentTimeMillis()))
                    saveSettings { it.copy(userStopped = false) }
                    startService()
                    refresh()
                }
            }
        }
    }

    /** Stops a running test, keeping what it measured; feeding restarts with the normal settings. */
    fun cancelPowerTest() {
        PowerTest.read(paths)?.let { PowerTest.write(paths, it.stop(System.currentTimeMillis(), "cancelled")) }
        restart()
    }

    fun clearPowerTest() {
        PowerTest.clear(paths)
        refreshNow()
    }

    fun restart() {
        FeederService.stop(context)
        viewModelScope.launch {
            delay(3_000)
            saveSettings { it.copy(userStopped = false) }
            startService()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /**
     * Plain-text bundle for "Share diagnostics". Contains the feeder UUID but no location: readsb
     * gets the position over gpsd, not its command line, and log lines pass through redactLocation.
     */
    fun diagnostics(): String {
        val s = _state.value
        val r = s.receiver
        return buildString {
            appendLine("${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_COMMIT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("Feeder: ${s.settings.name} / ${s.settings.uuid}")
            appendLine("Settings: feed=${s.settings.feedAdsbx} autoStart=${s.settings.autoStart} gain=${s.settings.gain} location=${s.settings.location?.source ?: "none"}")
            appendLine("SDR: ${s.sdr}")
            appendLine("Run: ${s.run}")
            if (r != null) appendLine("Receiver: live=${r.live} msg/s=${r.messagesPerSecond} aircraft=${r.aircraft.size} dropped=${r.samplesDropped} signal=${r.signalDbfs} noise=${r.noiseDbfs}")
            appendLine("Feed: feeding=${s.feedingAdsbx} state=${s.feed.state} reconnects=${s.feed.reconnects} sent=${s.feed.bytesSent} B upload=${s.feed.uploadBytesPerSec} B/s messages=${s.feed.messagesTotal} aircraftSeen=${s.feed.aircraftSeen}")
            s.mlat?.let { appendLine("MLAT: enabled=${s.settings.mlatEnabled} privacy=${s.settings.mlatPrivacy} antennaConfirmed=${s.settings.antennaConfirmed} name=\"${s.settings.name}\" $it") }
            appendLine("Stats uploads: setting=${s.settings.statsUploads} ${s.stats ?: "not running"}")
            s.health?.let { appendLine("Health: $it") }
            s.history?.days?.filter { it.batteryMeanW != null }?.takeLast(7)?.takeIf { it.isNotEmpty() }?.let { days ->
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                appendLine("On battery: " + days.joinToString { d ->
                    "${date.format(Date(d.day * HistoryRecorder.DAY_MS))} %.2f W over ${d.batteryMs / 60_000} min".format(Locale.US, d.batteryMeanW)
                })
            }
            s.powerTest?.let { appendLine("Power test: " + powerTestSummary(it)) }
            appendLine()
            appendLine("--- readsb log ---")
            r?.logTail?.forEach { appendLine(it) }
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private fun startService() {
        lastAutoStartMs = System.currentTimeMillis()
        runCatching { FeederService.start(context) }
            .onFailure { showMessage(context.getString(R.string.msg_start_failed, it.message ?: "")) }
    }

    /**
     * Plug and play: once set up, an attached SDR should just start feeding. Asks for USB
     * permission once per plug-in if needed. [force] skips the cooldown (explicit triggers).
     */
    private fun maybeAutoStart(force: Boolean) {
        val s = _state.value
        val settings = s.settings
        if (!settings.onboardingDone || !settings.autoStart || settings.userStopped) return
        if (s.run is RunState.Running || s.run is RunState.Starting) return
        // Paused: the service resumes by itself. Only if Android didn't bring it back after the
        // pause (see FeederService.pause) does starting it here pick the pause up again.
        if (s.run is RunState.Paused && feederProcessAlive()) return
        // Stopped after a crash loop: wait for the user, or a re-plug (force), to try again.
        if (!force && (s.run as? RunState.Stopped)?.reason == FeederStatus.Reason.SDR_FAILING) return
        if (!force && System.currentTimeMillis() - lastAutoStartMs < AUTO_START_COOLDOWN_MS) return
        when (val sdr = currentSdrState()) {
            is SdrState.Ready -> startService()
            is SdrState.NeedsPermission -> {
                val name = RtlSdrDevices.findAttached(context)?.deviceName
                if (name != permissionAskedFor) requestUsbPermission()
            }
            else -> Unit
        }
    }

    /** Get a position if we have none; otherwise occasionally check the phone hasn't moved. */
    private fun ensureLocation() {
        val s = _state.value.settings
        if (!s.onboardingDone || !locator.hasPermission() || locateJob?.isActive == true) return
        val saved = s.location
        if (saved == null) return acquireLocation(announce = false)
        if (saved.source == StationLocation.Source.MANUAL) return
        if (System.currentTimeMillis() - lastMoveCheckMs < MOVE_CHECK_INTERVAL_MS) return
        lastMoveCheckMs = System.currentTimeMillis()
        locateJob = viewModelScope.launch {
            val now = locator.quickFix() ?: return@launch
            val movedM = StationLocator.distanceM(saved, now.latitude, now.longitude)
            if (movedM > StationLocator.MOVED_THRESHOLD_M) {
                showMessage(context.getString(R.string.msg_moved))
                locateJob = null
                acquireLocation(announce = false)
            }
        }
    }

    private fun acquireLocation(announce: Boolean) {
        if (!locator.hasPermission()) return showMessage(context.getString(R.string.msg_need_location_permission))
        if (!locator.locationEnabled()) return showMessage(context.getString(R.string.msg_location_off))
        locateJob?.cancel()
        _state.update { it.copy(locating = LocatingProgress(null, 0)) }
        locateJob = viewModelScope.launch {
            val fix = locator.acquire { acc, n -> _state.update { it.copy(locating = LocatingProgress(acc, n)) } }
            _state.update { it.copy(locating = null) }
            if (fix == null) {
                showMessage(context.getString(R.string.msg_location_failed))
            } else {
                saveSettings { it.copy(location = fix) }
                if (announce) showMessage(context.getString(R.string.msg_location_updated))
            }
        }
    }

    private fun saveSettings(change: (FeederSettings) -> FeederSettings) {
        val s = FeederSettings.update(context, change)
        _state.update { it.copy(settings = s) }
    }

    private fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            val receiver = reader.read()
            val status = FeederStatus.read(paths.statusFile)
            // Rewritten by the service every 10 s at most while the app is open: read it only when it changes.
            val historyMtime = paths.historyFile.lastModified()
            if (historyMtime != lastHistoryMtime) {
                lastHistoryMtime = historyMtime
                lastHistory = History.read(paths)
            }
            Snapshot(
                settings = FeederSettings.load(context),
                sdr = currentSdrState(),
                receiver = receiver,
                run = runState(status, receiver),
                health = phoneHealth(),
                status = status,
                powerTest = PowerTest.read(paths),
            )
        }
        val feed = feedStats(snapshot)
        if (snapshot.run != RunState.Running) trails.clear()
        val trailMap = trails.update(snapshot.receiver.aircraft, System.currentTimeMillis())
        if (coachOpen) {
            val r = snapshot.receiver
            val recent = r.aircraft.filter { it.distanceNm != null && (it.seenSecondsAgo ?: 0.0) <= 60 }
            coach.sample(r.messagesPerSecond.takeIf { snapshot.run == RunState.Running && r.live }, recent.size,
                recent.maxOfOrNull { it.distanceNm!! })
        }
        _state.update {
            it.copy(
                settings = snapshot.settings, sdr = snapshot.sdr, run = snapshot.run, receiver = snapshot.receiver,
                health = snapshot.health, locationPermission = locator.hasPermission(),
                feedingAdsbx = snapshot.status?.feeding == true,
                feed = feed,
                mlat = snapshot.status?.mlat?.takeIf { snapshot.run == RunState.Running }
                    ?: MlatStatus(readiness = snapshot.settings.mlatReadiness()),
                history = lastHistory,
                trails = trailMap,
                coach = if (coachOpen) coach.state() else null,
                chargeProblem = snapshot.status?.chargeProblem == true && snapshot.run == RunState.Running,
                stats = snapshot.status?.stats?.takeIf { snapshot.run == RunState.Running },
                powerTest = snapshot.powerTest,
            )
        }
    }

    private data class Snapshot(
        val settings: FeederSettings,
        val sdr: SdrState,
        val receiver: ReceiverStatus,
        val run: RunState,
        val health: PhoneHealth,
        val status: FeederStatus?,
        val powerTest: PowerTest?,
    )

    private val trails = RadarTrails()

    // Rolling state for the feed card. Reset whenever a new run starts.
    private var feedRunStartedMs: Long? = null
    private val rateHistory = ArrayDeque<Float>()
    private var lastStatsNow: Double? = null
    private var lastBytesSent: Long? = null
    private var uploadBytesPerSec: Double? = null
    private var lastSampleMs = 0L

    private fun feedStats(s: Snapshot): FeedStats {
        val status = s.status
        val r = s.receiver
        if (status?.startedMs != feedRunStartedMs) {
            feedRunStartedMs = status?.startedMs
            rateHistory.clear()
            lastStatsNow = null
            lastBytesSent = null
            uploadBytesPerSec = null
        }
        val running = s.run == RunState.Running
        if (running) {
            val nowMs = System.currentTimeMillis()
            // Skip the first seconds of a run: the first rate readings cover a tiny interval and spike.
            val warmedUp = nowMs - (status?.startedMs ?: 0L) > WARMUP_MS
            if (warmedUp && nowMs - lastSampleMs >= 900) {
                lastSampleMs = nowMs
                rateHistory.addLast((r.messagesPerSecond ?: 0.0).toFloat())
            }
            while (rateHistory.size > HISTORY_SECONDS) rateHistory.removeFirst()
        }
        // readsb refreshes its byte counter about every 10 s; derive a rate between refreshes.
        // Its bytes_out includes the Beast data our own MLAT client reads on loopback; take it out.
        val now = r.statsNow
        val bytes = r.bytesSent?.let { (it - (status?.mlat?.loopbackBytes ?: 0)).coerceAtLeast(0) }
        if (now != null && bytes != null && now != lastStatsNow) {
            val prevNow = lastStatsNow
            val prevBytes = lastBytesSent
            if (prevNow != null && prevBytes != null && now > prevNow) {
                uploadBytesPerSec = (bytes - prevBytes).coerceAtLeast(0) / (now - prevNow)
            }
            lastStatsNow = now
            lastBytesSent = bytes
        }
        return FeedStats(
            state = if (running) status?.feedState ?: FeedState.UNKNOWN else FeedState.UNKNOWN,
            connectedSinceMs = status?.connectedSinceMs.takeIf { running },
            reconnects = status?.reconnects ?: 0,
            startedMs = status?.startedMs.takeIf { running },
            bytesSent = bytes.takeIf { running },
            uploadBytesPerSec = uploadBytesPerSec.takeIf { running },
            messagesTotal = r.messagesTotal.takeIf { running },
            aircraftSeen = r.aircraftSeen.takeIf { running },
            rateHistory = if (running) rateHistory.toList() else emptyList(),
        )
    }

    private fun currentSdrState(): SdrState {
        if (!RtlSdrDevices.phoneSupportsUsbHost(context)) return SdrState.NoUsbHost
        val dev = RtlSdrDevices.findAttached(context) ?: return SdrState.NotConnected
        val name = RtlSdrDevices.describe(dev)
        return if (RtlSdrDevices.hasPermission(context, dev)) SdrState.Ready(name) else SdrState.NeedsPermission(name)
    }

    /**
     * Combines the service's status file, readsb's output and exit code, and whether the
     * :feeder process exists. readsb ends by ending that process, so the exit code file and the
     * process list are how the UI learns it stopped.
     */
    private fun runState(status: FeederStatus?, receiver: ReceiverStatus): RunState {
        if (status == null) return RunState.Stopped(FeederStatus.Reason.NONE)
        val exitCode = FeederStatus.readExitCode(paths.exitFile)
        // Enumerating processes is a binder call: only when the files leave it open (B7).
        val alive by lazy { feederProcessAlive() }
        return when (status.state) {
            FeederStatus.State.STOPPED -> RunState.Stopped(status.reason)
            FeederStatus.State.PAUSED -> RunState.Paused(status.reason)
            FeederStatus.State.STARTING -> when {
                alive -> RunState.Starting
                else -> RunState.Failed(exitCode, receiver.lastError)
            }
            FeederStatus.State.RUNNING -> when {
                receiver.live && exitCode == null -> RunState.Running
                exitCode == 0 -> RunState.Stopped(FeederStatus.Reason.NONE)
                exitCode != null || !alive -> RunState.Failed(exitCode, receiver.lastError)
                // Just back on screen: readsb resumes aircraft.json within a few seconds.
                System.currentTimeMillis() - visibleSinceMs < RESUME_GRACE_MS -> RunState.Running
                else -> RunState.Starting // readsb hasn't written its first aircraft.json yet
            }
        }
    }

    /** Battery and thermal state change slowly: read every [HEALTH_EVERY_MS], not on every refresh. */
    private fun phoneHealth(): PhoneHealth {
        val now = System.currentTimeMillis()
        lastHealth?.let { if (now - lastHealthMs < HEALTH_EVERY_MS) return it }
        return PhoneHealth.read(context).also { lastHealth = it; lastHealthMs = now }
    }

    private fun feederProcessAlive(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val name = "${context.packageName}:feeder"
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.adsbexchange.feeder.USB_PERMISSION"
        private const val POLL_MS = 1_000L
        private const val AUTO_START_COOLDOWN_MS = 30_000L
        private const val MOVE_CHECK_INTERVAL_MS = 60 * 60 * 1000L
        const val HISTORY_SECONDS = 300
        private const val RESUME_GRACE_MS = 8_000L
        private const val WARMUP_MS = 5_000L
        private const val HEALTH_EVERY_MS = 5_000L
    }
}
