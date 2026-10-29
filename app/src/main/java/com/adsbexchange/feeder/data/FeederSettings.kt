package com.adsbexchange.feeder.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Where the app and readsb keep their files. Shared by the UI process and the `:feeder` process. */
class FeederPaths(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    val root = File(filesDir, "readsb")
    val jsonDir = File(root, "json")
    val logFile = File(root, "readsb.log")
    /** The feeder ID for readsb (`--uuid-file`), so it isn't on a command line that gets logged. */
    val uuidFile = File(root, "feeder-id")
    val aircraftJson = File(jsonDir, "aircraft.json")
    val statsJson = File(jsonDir, "stats.json")
    val receiverJson = File(jsonDir, "receiver.json")
    /** Written by the feeder service; see [FeederStatus]. */
    val statusFile = File(filesDir, "feeder_status.json")
    /** Written by the native bridge with readsb's exit code just before the :feeder process ends. */
    val exitFile = File(root, "readsb_exit")
    val settingsFile = File(filesDir, "settings.json")
    /** Locked by whichever process is writing [settingsFile]. */
    val settingsLockFile = File(filesDir, "settings.lock")
    /** Written by the UI; see [UiVisibility]. */
    val uiVisibleFile = File(filesDir, "ui_visible")
    /** Written by the feeder service; see [HistoryStore]. */
    val historyFile = File(filesDir, "history.json")
    val historyAircraftFile = File(filesDir, "history_aircraft.bin")
    /** Written by the feeder service; see [BatteryGuard.Hold] and [RestartBudget]. */
    val holdFile = File(filesDir, "feeder_hold")
    val restartsFile = File(filesDir, "feeder_restarts")
    /** Written by the UI (start, cancel) and the feeder service (progress); see [PowerTest]. */
    val powerTestFile = File(filesDir, "power_test.json")
}

/** Where the receiver is. Altitude is kept for a future MLAT client; readsb doesn't need it. */
data class StationLocation(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val timeMs: Long,
    val source: Source,
) {
    enum class Source { GPS, NETWORK, MANUAL }

    fun toJson(): JSONObject = JSONObject()
        .put("lat", lat).put("lon", lon)
        .put("alt", altitudeM ?: JSONObject.NULL)
        .put("acc", accuracyM?.toDouble() ?: JSONObject.NULL)
        .put("time", timeMs).put("source", source.name)

    companion object {
        fun fromJson(o: JSONObject?): StationLocation? = o?.let {
            StationLocation(
                lat = it.getDouble("lat"),
                lon = it.getDouble("lon"),
                altitudeM = it.optDoubleOrNull("alt"),
                accuracyM = it.optDoubleOrNull("acc")?.toFloat(),
                timeMs = it.optLong("time"),
                source = runCatching { Source.valueOf(it.optString("source")) }.getOrDefault(Source.MANUAL),
            )
        }
    }
}

/**
 * User settings.
 *
 * Stored as a JSON file rather than SharedPreferences: the UI process writes it and the `:feeder`
 * process reads it at every start, and SharedPreferences caches values per process, so a cached
 * :feeder process could otherwise start with stale settings.
 */
data class FeederSettings(
    val uuid: String,
    val name: String,
    val location: StationLocation?,
    /** Keep updating the receiver position from GPS while feeding (car, boat, travel). */
    val gain: String = "auto",
    val feedAdsbx: Boolean = true,
    /** Start when the SDR is plugged in and after the phone restarts. */
    val autoStart: Boolean = true,
    /** Set when the user taps Stop, so boot/plug-in don't restart what they deliberately stopped. */
    val userStopped: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Take part in ADS-B Exchange MLAT. Needed for the feeder name to be used. See [mlatReadiness]. */
    val mlatEnabled: Boolean = true,
    /**
     * mlat-client --privacy: the public MLAT map shows no location for this feeder. Without it, the
     * map shows one rounded to about 1 km. The name is listed either way. Off to start with, as on
     * ADS-B Exchange's own feeders (with it on, their linked-receivers page hides the feeder's map
     * marker and its feed and stats links); a settings file from before keeps what it has.
     */
    val mlatPrivacy: Boolean = false,
    /**
     * The antenna is within about 15 m of the phone, so MLAT can use the phone's position. Ticked to
     * start with: on a phone the antenna is nearly always on a short cable. Saved as
     * "antennaWithin15m"; 1.0.0's unticked-by-default "antennaConfirmed" is ignored, so a feeder that
     * missed the box gets MLAT after updating.
     */
    val antennaWithin15m: Boolean = true,
    /** Antenna height in metres, if entered by hand; otherwise the GPS altitude is used. */
    val antennaAltitudeM: Double? = null,
    /** Alert when feeding has stopped working (see [FeedWatchdog]). */
    val feedAlerts: Boolean = true,
    /** Keep feeding when the phone is on mobile data (or another metered network); see [NetworkGuard]. */
    val feedOverMobileData: Boolean = false,
    /** The user asked to restart the range plot at this time; the feeder service applies it. */
    val rangeResetMs: Long? = null,
    /** The radar's range in nm, or 0 to fit the aircraft heard (see [com.adsbexchange.feeder.ui.RadarMath.autoRange]). */
    val radarRangeNm: Int = 0,
    /** Power an amplifier at the antenna through the cable (readsb's --enable-biastee). Off by default. */
    val biasTee: Boolean = false,
    /** Nearby airports on the radar. */
    val radarAirports: Boolean = true,
) {
    /**
     * readsb's command line.
     *
     * @param gpsdPort port of the in-app [com.adsbexchange.feeder.service.GpsdServer], the only
     *   way the receiver position reaches readsb.
     * @param inputFile debug smoke tests only: read UC8 I/Q samples from this file at real-time
     *   speed instead of the SDR.
     * @param feedTo where to send the feed, as "host,port" (see [ADSBX_FEED]), or null not to feed.
     * @param beastPort loopback port for readsb's Beast output, which the MLAT client reads; 0 for none.
     */
    fun readsbArgs(
        paths: FeederPaths, gpsdPort: Int, feedTo: String?, inputFile: File? = null, beastPort: Int = 0,
    ): List<String> = buildList {
        if (inputFile != null) {
            add("--device-type"); add("ifile")
            add("--ifile"); add(inputFile.absolutePath)
            add("--iformat"); add("UC8")
            add("--throttle")
        } else {
            add("--device-type"); add("rtlsdr")
            add("--gain"); add(gain.ifBlank { "auto" })
            // About 4.5 V on the antenna socket, for an amplifier at the antenna (see biasTee).
            if (biasTee) add("--enable-biastee")
        }
        // No --lat/--lon: readsb (and our start line) print the whole command line to the log, which
        // ends up in "Share diagnostics" and logcat. The position arrives over gpsd instead.
        // Without --quiet readsb prints every decoded message to stdout (i.e. our log file).
        add("--quiet")
        add("--db-file"); add("none")
        add("--write-json"); add(paths.jsonDir.absolutePath)
        add("--write-json-every"); add("1")
        add("--json-location-accuracy"); add("2")
        add("--net")
        // Only expose ports on loopback. The Beast output is for the MLAT client, on a port picked
        // afresh for each run rather than the well-known 30005: another app holding 30005 would
        // otherwise stop readsb starting, and the client checks the port is readsb's (see MlatClient).
        add("--net-bind-address"); add("127.0.0.1")
        add("--net-bo-port"); add(beastPort.toString())
        add("--net-ro-port"); add("0")
        add("--net-ri-port"); add("0")
        add("--net-bi-port"); add("0")
        add("--net-sbs-port"); add("0")
        add("--net-connector"); add("127.0.0.1,$gpsdPort,gpsd_in")
        if (feedTo != null) {
            // The feeder ID comes from a file ([FeederPaths.uuidFile], written by the service), not
            // `,uuid=` here: readsb and the app both log the command line, and the log goes out
            // with Share diagnostics. readsb sends it on the feed all the same.
            add("--uuid-file"); add(paths.uuidFile.absolutePath)
            // primary-host,port,protocol[,failover-host,port]
            val endpoints = feedEndpoints(feedTo)
            add("--net-connector"); add((listOf(endpoints.first(), "beast_reduce_plus_out") + endpoints.drop(1)).joinToString(","))
            // As ADS-B Exchange's feeder kit sends it (configure.sh: REDUCE_INTERVAL 0.5,
            // --net-ro-interval 0.2): each aircraft at most twice a second, sent in batches every
            // 0.2 s. readsb's defaults (0.25 s, flushed within 50 ms) send twice the updates and
            // wake the radio more often. Only for this connector: the MLAT client's loopback feed
            // keeps readsb's defaults.
            add("--net-beast-reduce-interval"); add("0.5")
            add("--net-ro-interval-beast-reduce"); add("0.2")
        }
    }

    private fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("name", name)
        .put("location", location?.toJson() ?: JSONObject.NULL)
        .put("gain", gain).put("feedAdsbx", feedAdsbx)
        .put("autoStart", autoStart).put("userStopped", userStopped)
        .put("onboardingDone", onboardingDone)
        .put("mlatEnabled", mlatEnabled).put("mlatPrivacy", mlatPrivacy)
        .put("antennaWithin15m", antennaWithin15m)
        .put("antennaAltitudeM", antennaAltitudeM ?: JSONObject.NULL)
        .put("feedAlerts", feedAlerts).put("rangeResetMs", rangeResetMs ?: JSONObject.NULL)
        .put("radarRangeNm", radarRangeNm)
        .put("biasTee", biasTee).put("radarAirports", radarAirports)
        .put("feedOverMobileData", feedOverMobileData)

    companion object {
        const val ADSBX_HOST = "feed1.adsbexchange.com"
        /** readsb connector target for the ADS-B Exchange feed. */
        const val ADSBX_FEED = "$ADSBX_HOST,30004"
        /**
         * Where readsb switches to while [ADSBX_FEED] can't be reached, as ADS-B Exchange's own
         * feeder image does (`feed1.adsbexchange.com,30004,beast_reduce_out,feed2.adsbexchange.com,64004`).
         */
        const val ADSBX_FEED_FAILOVER = "feed2.adsbexchange.com,64004"

        /** The feed's "host,port" endpoints: [feedTo], then its failover if it has one. */
        fun feedEndpoints(feedTo: String): List<String> =
            if (feedTo == ADSBX_FEED) listOf(ADSBX_FEED, ADSBX_FEED_FAILOVER) else listOf(feedTo)
        const val MAX_NAME_LENGTH = 40

        /**
         * Ends every feeder UUID this app creates ("ADSB cell", in hex), so ADS-B Exchange can count
         * phone feeders on the feed and in its stats, e.g. `uuid LIKE '%ad5bce11'`. It has to be
         * hex: readsb on ADS-B Exchange's end stops reading a UUID at the first non-hex character.
         * It goes at the end because feeder pages show the first 8 characters, which stay random.
         */
        const val PHONE_UUID_TAG = "ad5bce11"

        /** A random (version 4) UUID whose last 8 hex digits are [PHONE_UUID_TAG]. */
        fun newFeederUuid(): String = UUID.randomUUID().toString().dropLast(PHONE_UUID_TAG.length) + PHONE_UUID_TAG

        private val UUID_FORMAT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val lock = Any()

        fun load(context: Context): FeederSettings = load(FeederPaths(context))

        /** Read-modify-write, so concurrent writers, in either process, each apply their own change. */
        fun update(context: Context, change: (FeederSettings) -> FeederSettings): FeederSettings =
            update(FeederPaths(context), change)

        fun load(paths: FeederPaths): FeederSettings =
            quickRead(paths)?.takeIf { it.uuid.isNotBlank() } ?: locked(paths) { loadLocked(paths).settings }

        /**
         * Read-modify-write under the lock. Not saved while settings.json can't be read (see
         * [loadLocked]), so a change then can't write defaults over it.
         */
        fun update(paths: FeederPaths, change: (FeederSettings) -> FeederSettings): FeederSettings =
            locked(paths) {
                val loaded = loadLocked(paths)
                change(loaded.settings).also {
                    if (loaded.writable) write(paths, it)
                    else appendAppLog(paths, "settings: change not saved, because settings.json can't be read")
                }
            }

        /**
         * Both processes write settings (the UI on every change, the service when Stop is tapped in
         * the notification), so a JVM lock isn't enough: writers also hold a file lock, which the
         * kernel shares across processes. The JVM lock is still needed because a second lock on
         * the same file from the same process throws rather than waits.
         */
        private fun <T> locked(paths: FeederPaths, block: () -> T): T = synchronized(lock) {
            RandomAccessFile(paths.settingsLockFile, "rw").use { file ->
                file.channel.lock()
                block() // closing the file releases the lock
            }
        }

        /** [loadLocked]'s result; [writable] is false while settings.json can't be read, so it isn't written over. */
        private class Loaded(val settings: FeederSettings, val writable: Boolean)

        /**
         * First run, or a missing or damaged settings.json: start from defaults, but keep the feeder
         * ID if readsb's copy of it survives, so the feeder keeps its identity (and its stats on
         * ADS-B Exchange) rather than appearing as a new one. A damaged file is copied aside first
         * (`settings.json.unreadable-<date-time>`), so the name, position and switches in it can
         * still be recovered.
         *
         * A settings.json that is there but can't be read at all, even after about a second of
         * retries, isn't replaced: this returns defaults for now, with the feeder ID from readsb's
         * copy or blank, and [update] doesn't save over it. The next read that works gets everything
         * back. (As the desktop feeder, code review DF-CQ-03.)
         */
        private fun loadLocked(paths: FeederPaths): Loaded {
            val text = try {
                readTextIfExists(paths.settingsFile)
            } catch (e: IOException) {
                appendAppLog(paths, "settings: settings.json can't be read ($e); using defaults for now, and not saving over it")
                return Loaded(FeederSettings(uuid = recoveredUuid(paths) ?: "", name = "", location = null), writable = false)
            }
            val s = text?.let(::parse)
            if (s != null && s.uuid.isNotBlank()) return Loaded(s, writable = true)
            if (text != null && s == null) {
                val copy = keepUnreadableCopy(paths.settingsFile)
                appendAppLog(paths, "settings: settings.json couldn't be understood; " +
                    (copy?.let { "kept a copy as ${it.name}" } ?: "couldn't keep a copy") + "; starting from defaults")
            }
            val uuid = recoveredUuid(paths) ?: newFeederUuid()
            val result = s?.copy(uuid = uuid) ?: FeederSettings(uuid = uuid, name = "", location = null)
            write(paths, result)
            return Loaded(result, writable = true)
        }

        private fun recoveredUuid(paths: FeederPaths): String? =
            runCatching { paths.uuidFile.readText().trim() }.getOrNull()?.takeIf(UUID_FORMAT::matches)

        /** Without the lock, for the common case; anything unusual is left to [loadLocked]. */
        private fun quickRead(paths: FeederPaths): FeederSettings? =
            runCatching { paths.settingsFile.readText() }.getOrNull()?.let(::parse)

        private fun parse(text: String): FeederSettings? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return FeederSettings(
                uuid = o.optString("uuid"),
                name = o.optString("name"),
                location = StationLocation.fromJson(o.optJSONObject("location")),
                gain = o.optString("gain", "auto").ifBlank { "auto" },
                feedAdsbx = o.optBoolean("feedAdsbx", true),
                autoStart = o.optBoolean("autoStart", true),
                userStopped = o.optBoolean("userStopped", false),
                onboardingDone = o.optBoolean("onboardingDone", false),
                mlatEnabled = o.optBoolean("mlatEnabled", true),
                mlatPrivacy = o.optBoolean("mlatPrivacy", false),
                antennaWithin15m = o.optBoolean("antennaWithin15m", true),
                antennaAltitudeM = o.optDoubleOrNull("antennaAltitudeM"),
                feedAlerts = o.optBoolean("feedAlerts", true),
                feedOverMobileData = o.optBoolean("feedOverMobileData", false),
                rangeResetMs = o.optDoubleOrNull("rangeResetMs")?.toLong(),
                radarRangeNm = o.optInt("radarRangeNm", 0),
                biasTee = o.optBoolean("biasTee", false),
                radarAirports = o.optBoolean("radarAirports", true),
            )
        }

        /** Also keeps readsb's copy of the feeder ID current, which doubles as its backup. */
        private fun write(paths: FeederPaths, s: FeederSettings) {
            paths.settingsFile.writeAtomically(s.toJson().toString(2))
            if (runCatching { paths.uuidFile.readText().trim() }.getOrNull() != s.uuid) {
                paths.root.mkdirs()
                paths.uuidFile.writeAtomically(s.uuid)
            }
        }

        /**
         * As the ADSBx feeder kits do (configure.sh): letters, digits, spaces, '_' and '-' are kept,
         * anything else becomes '_'. Blank is allowed: the name is optional.
         */
        fun sanitizeName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").take(MAX_NAME_LENGTH)
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
