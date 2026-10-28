package com.adsbexchange.feeder.data

import org.json.JSONObject
import java.io.File

/**
 * What the feeder service is doing, written by the `:feeder` process for the UI process to read.
 *
 * A status file rather than binder IPC: the :feeder process ends abruptly whenever readsb exits,
 * and a file survives that. The UI combines it with readsb's own output and exit code; see
 * [com.adsbexchange.feeder.ui.MainViewModel].
 */
data class FeederStatus(
    val state: State,
    val reason: Reason = Reason.NONE,
    /**
     * Whether this run sends data to ADS-B Exchange. Can differ from the saved setting: the setting
     * only applies at the next start, and smoke-test runs feed a local sink instead.
     */
    val feeding: Boolean = false,
    /** readsb feed connector target as "host,port" (feed1.adsbexchange.com,30004 or a test sink). */
    val feedTarget: String? = null,
    val feedState: FeedState = FeedState.UNKNOWN,
    /** When the current feed connection was established, if it's up. */
    val connectedSinceMs: Long? = null,
    /** Times the feed connection has dropped during this run. */
    val reconnects: Int = 0,
    /** When readsb started for this run. */
    val startedMs: Long? = null,
    val mlat: MlatStatus? = null,
    /** The battery is running down although the phone has been on a charger (see [ChargeWatchdog]). */
    val chargeProblem: Boolean = false,
    /** Stats uploads (see [com.adsbexchange.feeder.service.StatsUploader]) while they run. */
    val stats: StatsStatus? = null,
    val updatedMs: Long = System.currentTimeMillis(),
) {
    /** PAUSED: waiting, without readsb, until the battery is safe again; see [BatteryGuard]. */
    enum class State { STOPPED, STARTING, RUNNING, PAUSED }

    enum class Reason {
        NONE,
        USER_STOPPED,
        SDR_UNPLUGGED,
        NO_SDR,
        NO_PERMISSION,
        START_FAILED,
        /** Paused: the phone is too hot. */
        HOT,
        /** Paused: the battery is nearly flat and not charging. */
        LOW_BATTERY,
        /** Stopped: readsb kept exiting; see [RestartBudget]. */
        SDR_FAILING,
        /** Paused: feeding would use mobile data, which the settings don't allow; see [NetworkGuard]. */
        MOBILE_DATA,
    }

    fun write(file: File) {
        val o = JSONObject()
            .put("state", state.name).put("reason", reason.name).put("updated", updatedMs)

            .put("feeding", feeding)
            .put("feedTarget", feedTarget ?: JSONObject.NULL)
            .put("feedState", feedState.name)
            .put("connectedSince", connectedSinceMs ?: JSONObject.NULL)
            .put("reconnects", reconnects)
            .put("started", startedMs ?: JSONObject.NULL)
            .put("mlat", mlat?.toJson() ?: JSONObject.NULL)
            .put("chargeProblem", chargeProblem)
            .put("stats", stats?.toJson() ?: JSONObject.NULL)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    companion object {
        fun read(file: File): FeederStatus? = runCatching {
            val o = JSONObject(file.readText())
            FeederStatus(
                state = State.valueOf(o.getString("state")),
                reason = runCatching { Reason.valueOf(o.optString("reason")) }.getOrDefault(Reason.NONE),
                feeding = o.optBoolean("feeding", false),
                feedTarget = o.optString("feedTarget").takeIf { it.isNotBlank() && !o.isNull("feedTarget") },
                feedState = runCatching { FeedState.valueOf(o.optString("feedState")) }.getOrDefault(FeedState.UNKNOWN),
                connectedSinceMs = o.optLong("connectedSince").takeIf { !o.isNull("connectedSince") && it > 0 },
                reconnects = o.optInt("reconnects", 0),
                chargeProblem = o.optBoolean("chargeProblem", false),
                stats = StatsStatus.fromJson(o.optJSONObject("stats")),
                startedMs = o.optLong("started").takeIf { !o.isNull("started") && it > 0 },
                mlat = MlatStatus.fromJson(o.optJSONObject("mlat")),
                updatedMs = o.optLong("updated"),
            )
        }.getOrNull()

        /** readsb's exit code, if the last run has ended. Cleared at every start by the bridge. */
        fun readExitCode(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()
    }
}

/** MLAT client state for the UI, published by the service inside [FeederStatus]. */
data class MlatStatus(
    val readiness: MlatReadiness,
    /** [com.adsbexchange.feeder.mlat.MlatClient.State] name while the client runs. */
    val state: String? = null,
    val server: String? = null,
    val connectedSinceMs: Long? = null,
    val peerCount: Int? = null,
    val outlierPercent: Double? = null,
    val badSyncTimeout: Int? = null,
    val requested: Int = 0,
    val adsbAircraft: Int = 0,
    val modesAircraft: Int = 0,
    val syncsSent: Long = 0,
    val mlatSent: Long = 0,
    val bytesSent: Long = 0,
    /**
     * This readsb session's Beast data read by the MLAT client on loopback, as of readsb's current
     * stats.json. readsb counts it in its bytes_out, so it's subtracted to get what the feed sent.
     */
    val loopbackBytes: Long = 0,
    /** Server message of the day, or why it refused the connection. */
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("readiness", readiness.name).put("state", state ?: JSONObject.NULL)
        .put("server", server ?: JSONObject.NULL).put("connectedSince", connectedSinceMs ?: JSONObject.NULL)
        .put("peerCount", peerCount ?: JSONObject.NULL).put("outlierPercent", outlierPercent ?: JSONObject.NULL)
        .put("badSyncTimeout", badSyncTimeout ?: JSONObject.NULL)
        .put("requested", requested).put("adsbAircraft", adsbAircraft).put("modesAircraft", modesAircraft)
        .put("syncsSent", syncsSent).put("mlatSent", mlatSent).put("bytesSent", bytesSent)
        .put("loopbackBytes", loopbackBytes).put("message", message ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): MlatStatus? = o?.let {
            MlatStatus(
                readiness = runCatching { MlatReadiness.valueOf(it.optString("readiness")) }.getOrDefault(MlatReadiness.OFF),
                state = it.optStringOrNull("state"),
                server = it.optStringOrNull("server"),
                connectedSinceMs = it.optDoubleOrNull("connectedSince")?.toLong(),
                peerCount = it.optDoubleOrNull("peerCount")?.toInt(),
                outlierPercent = it.optDoubleOrNull("outlierPercent"),
                badSyncTimeout = it.optDoubleOrNull("badSyncTimeout")?.toInt(),
                requested = it.optInt("requested"),
                adsbAircraft = it.optInt("adsbAircraft"),
                modesAircraft = it.optInt("modesAircraft"),
                syncsSent = it.optLong("syncsSent"),
                mlatSent = it.optLong("mlatSent"),
                bytesSent = it.optLong("bytesSent"),
                loopbackBytes = it.optLong("loopbackBytes"),
                message = it.optStringOrNull("message"),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

/** Stats uploads for the UI, published by the service inside [FeederStatus]. */
data class StatsStatus(
    val uploads: Long = 0,
    val bytesSent: Long = 0,
    val lastOkMs: Long? = null,
    /** Why the latest upload failed; null once one succeeds. */
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uploads", uploads).put("bytesSent", bytesSent)
        .put("lastOk", lastOkMs ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): StatsStatus? = o?.let {
            StatsStatus(
                uploads = it.optLong("uploads"), bytesSent = it.optLong("bytesSent"),
                lastOkMs = it.optDoubleOrNull("lastOk")?.toLong(), error = it.optStringOrNull("error"),
            )
        }
    }
}
