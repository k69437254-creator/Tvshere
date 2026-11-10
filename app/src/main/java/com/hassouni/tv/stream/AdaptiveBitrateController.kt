package com.hassouni.tv.stream

/** Bitrate adaptation driven by the sender's congestion and dropped-frame counters. */
class AdaptiveBitrateController(
    initialKbps: Int,
    private val minKbps: Int = 300,
    private val maxKbps: Int = initialKbps
) {
    private var currentKbps = initialKbps.coerceIn(minKbps, maxKbps)
    private var previousDropped = 0
    private var stableSamples = 0

    /** Returns a new target only when a change is justified; otherwise null. */
    fun update(congested: Boolean, droppedFrames: Int): Int? {
        val newlyDropped = (droppedFrames - previousDropped).coerceAtLeast(0)
        previousDropped = droppedFrames
        if (congested || newlyDropped > 0) {
            stableSamples = 0
            val next = (currentKbps * 0.82f).toInt().coerceAtLeast(minKbps)
            if (next != currentKbps) {
                currentKbps = next
                return currentKbps
            }
            return null
        }
        stableSamples++
        if (stableSamples >= 12 && currentKbps < maxKbps) {
            stableSamples = 0
            currentKbps = (currentKbps * 1.04f).toInt().coerceAtMost(maxKbps)
            return currentKbps
        }
        return null
    }

    fun reset(kbps: Int) {
        currentKbps = kbps.coerceIn(minKbps, maxKbps)
        previousDropped = 0
        stableSamples = 0
    }
}
