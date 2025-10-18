package com.devrhythm

import com.intellij.util.messages.Topic

/**
 * Per-second tick broadcast from IdleTracker, used by status-bar widget.
 *
 * Note: isIdle reflects the current inactivity threshold (now 5 minutes),
 * not the older 20s value.
 */
interface DevRhythmEvents {
    companion object {
        val TOPIC: Topic<DevRhythmEvents> =
            Topic.create("DevRhythmEvents", DevRhythmEvents::class.java)
    }

    /**
     * @param totalProjectMs total project time (ms) including CSV history + this session
     * @param totalActiveMs total active time (ms), CSV history + live (only after session is armed)
     * @param isIdle whether we're currently idle based on the 5-minute inactivity threshold
     */
    fun onTick(totalProjectMs: Long, totalActiveMs: Long, isIdle: Boolean)
}
