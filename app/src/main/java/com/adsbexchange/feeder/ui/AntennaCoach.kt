package com.adsbexchange.feeder.ui

/**
 * The antenna coach: live reception numbers to move the antenna by, and a short measurement of
 * each spot to compare them. Fed one sample a second by the view model while the coach is open,
 * from what the screen already reads; nothing extra runs in the feeder.
 */
class AntennaCoach(private val samplesPerSpot: Int = SAMPLES_PER_SPOT) {
    data class Spot(val label: String, val messagesPerSecond: Double, val aircraft: Int, val rangeNm: Double?)

    data class State(
        /** Smoothed, so the number settles a few seconds after the antenna moves. */
        val messagesPerSecond: Double? = null,
        val aircraft: Int = 0,
        val rangeNm: Double? = null,
        /** 0..1 while a spot is being measured, else null. */
        val measuring: Float? = null,
        val measuringLabel: String? = null,
        /** Best first. */
        val spots: List<Spot> = emptyList(),
    )

    private var smoothed: Double? = null
    private var aircraft = 0
    private var range: Double? = null
    private val spots = ArrayList<Spot>()

    private var label: String? = null
    private var count = 0
    private var rateSum = 0.0
    private var maxAircraft = 0
    private var maxRange: Double? = null

    /** One reading: the current message rate, aircraft with a recent position, and the furthest of them. */
    fun sample(messagesPerSecond: Double?, aircraftWithPosition: Int, furthestNm: Double?) {
        if (messagesPerSecond != null) {
            smoothed = smoothed?.let { it + SMOOTHING * (messagesPerSecond - it) } ?: messagesPerSecond
        }
        aircraft = aircraftWithPosition
        range = furthestNm
        val l = label ?: return
        if (messagesPerSecond == null) return // the receiver isn't producing numbers yet
        count++
        rateSum += messagesPerSecond
        maxAircraft = maxOf(maxAircraft, aircraftWithPosition)
        furthestNm?.let { maxRange = maxOf(maxRange ?: 0.0, it) }
        if (count >= samplesPerSpot) {
            spots += Spot(l, rateSum / count, maxAircraft, maxRange)
            label = null
        }
    }

    fun measure(spotLabel: String) {
        label = spotLabel.trim().ifBlank { "Spot ${spots.size + 1}" }
        count = 0
        rateSum = 0.0
        maxAircraft = 0
        maxRange = null
    }

    fun cancel() {
        label = null
    }

    fun remove(spot: Spot) {
        spots.remove(spot)
    }

    fun state() = State(
        messagesPerSecond = smoothed, aircraft = aircraft, rangeNm = range,
        measuring = label?.let { count.toFloat() / samplesPerSpot },
        measuringLabel = label,
        spots = spots.sortedByDescending { it.messagesPerSecond },
    )

    companion object {
        /** 30 one-second samples: long enough to average out bursts, short enough to try several spots. */
        const val SAMPLES_PER_SPOT = 30
        private const val SMOOTHING = 0.3
    }
}
