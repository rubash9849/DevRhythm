package com.devrhythm

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.messages.MessageBusConnection
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.Timer
import kotlin.math.max
import java.util.ArrayDeque

/**
 * Status bar widget:
 *  - Shows idle by default (before any input).
 *  - Total Active Time reflects CSV until session is "armed" (2 inputs).
 *  - "( ... of last 30 mins )" is computed with STRICT CLOSED-GAP rule, but now displayed as rounded minutes (and hours if needed).
 *    Window = 1800s, refresh cadence = anchored every 60s (…:00, …:01:00, …).
 *  - Blanks when project disabled or status == "Do not Show".
 *
 * Sliding-window math (unchanged):
 *  - Count only inter-input *closed* gaps <= BREAK_THRESHOLD_MS (5 minutes).
 *  - Left-clipped at window start; no right-edge tail.
 */
class DevRhythmStatusBarWidget(private val project: Project) :
    CustomStatusBarWidget, Disposable {

    companion object {
        const val WIDGET_ID: String = "DevRhythmStatusBarWidget"

        // Sliding-window configuration (unchanged)
        private const val WINDOW_SECONDS = 1800              // 30 minutes
        private const val SAMPLE_SECONDS = 60               // recompute on exact minute boundaries

        // Activity threshold (unchanged): gaps <= 5 min are "active"; > 5 min is a break.
        private const val BREAK_THRESHOLD_MS = 300_000L     // 5 minutes

        // Keep recent inputs for at least the window + buffer
        private const val KEEP_MS = 40 * 60 * 1000L         // 40 minutes
    }

    private var statusBar: StatusBar? = null
    private var conn: MessageBusConnection? = null
    private val label = JLabel("")  // start blank; first tick will render
    private var lastText: String = label.text

    // Inputs (epochMillis) for strict CLOSED-GAP logic
    private val inputs: ArrayDeque<Long> = ArrayDeque()

    // Cached “last 30 mins” text, recomputed on minute boundaries (minutes-only display)
    private var cachedLast30MinText: String = "( 0min of last 30 mins )"
    private var lastBucketIndex: Long = -1L  // floor(nowSec / 60)

    // Last totals from per-second ticks
    private var lastTotalActiveMs: Long = 0L
    private var lastIsIdle: Boolean = true

    // Guard timer to keep blank when disabled / do-not-show
    private var guardTimer: Timer? = null

    override fun ID(): String = WIDGET_ID

    private fun isSessionArmed(): Boolean =
        project.getUserData(com.devrhythm.IdleTracker.SESSION_ARMED_KEY) == true

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        conn = project.messageBus.connect(this).also { c ->
            // Per-second tick from IdleTracker
            c.subscribe(
                com.devrhythm.DevRhythmEvents.TOPIC,
                object : com.devrhythm.DevRhythmEvents {
                    override fun onTick(totalProjectMs: Long, totalActiveMs: Long, isIdle: Boolean) {
                        // If plugin disabled / do-not-show: blank and bail
                        if (project.getUserData(com.devrhythm.IdleToolWindowFactory.DISABLED_KEY) == true ||
                            project.getUserData(com.devrhythm.IdleToolWindowFactory.STATUS_KEY) == "Do not Show"
                        ) {
                            blankAndReset()
                            return
                        }

                        lastTotalActiveMs = totalActiveMs
                        lastIsIdle = isIdle

                        // --- Anchor recompute to true 60s boundaries ---
                        val nowMs = System.currentTimeMillis()
                        val nowSec = nowMs / 1000L
                        val bucket = nowSec / SAMPLE_SECONDS  // floor division
                        if (isSessionArmed()) {
                            if (bucket != lastBucketIndex) {
                                lastBucketIndex = bucket
                                val bucketEndMs = bucket * SAMPLE_SECONDS * 1000L  // exact boundary time
                                val activeSec = computeActiveInLastWindowAt(bucketEndMs)
                                // Minutes-only (rounded to nearest minute; >=30s -> round up)
                                cachedLast30MinText = "(${formatMinutesRounded(activeSec)} of last 30 mins)"
                            }
                        } else {
                            // not armed → show zero and reset anchor so we start fresh when armed
                            cachedLast30MinText = "( 0min of last 30 mins )"
                            lastBucketIndex = -1L
                        }

                        updateLabel()
                    }
                }
            )

            // Raw input timestamps (epoch millis) to compute last-30-min strictly
            c.subscribe(
                com.devrhythm.DevRhythmInputEvents.TOPIC,
                object : com.devrhythm.DevRhythmInputEvents {
                    override fun onInput(epochMillis: Long) {
                        if (project.getUserData(com.devrhythm.IdleToolWindowFactory.DISABLED_KEY) == true ||
                            project.getUserData(com.devrhythm.IdleToolWindowFactory.STATUS_KEY) == "Do not Show"
                        ) return
                        inputs.addLast(epochMillis)
                        trimOldInputs(epochMillis)
                        // NOTE: we do NOT recompute here; recompute only at 60s boundaries.
                    }
                }
            )
        }

        // Guard: blank if disabled or status flips late
        guardTimer = Timer(1000) {
            if (project.getUserData(com.devrhythm.IdleToolWindowFactory.DISABLED_KEY) == true ||
                project.getUserData(com.devrhythm.IdleToolWindowFactory.STATUS_KEY) == "Do not Show"
            ) {
                blankAndReset()
            }
        }.apply { isRepeats = true; start() }
    }

    override fun getComponent(): JComponent = label

    override fun dispose() {
        conn?.disconnect()
        conn = null
        guardTimer?.stop()
        guardTimer = null
        inputs.clear()
        cachedLast30MinText = "( 0min of last 30 mins )"
        lastBucketIndex = -1L
        lastTotalActiveMs = 0L
        lastIsIdle = true
    }

    /**
     * STRICT CLOSED-GAP window computation at a specific boundary "endMs":
     * - Count only inter-input gaps <= BREAK_THRESHOLD_MS that are closed by the next input.
     * - Left-CLIPPED carry-in for a gap starting before winStart.
     * - No right-edge tail (lastInput -> endMs).
     */
    private fun computeActiveInLastWindowAt(endMs: Long): Int {
        val winEnd = endMs
        val winStart = winEnd - WINDOW_SECONDS * 1000L

        // Keep deque small and relevant
        trimOldInputs(winEnd)

        var prevBeforeWindow: Long? = null
        val windowInputs = ArrayList<Long>()

        // Partition inputs: remember last before window; collect those inside window
        for (t in inputs) {
            if (t < winStart) {
                prevBeforeWindow = t
            } else if (t <= winEnd) {
                windowInputs.add(t)
            } else {
                break
            }
        }

        var activeMs = 0L

        // A) Carry-in: LEFT-CLIPPED overlap of (prevBeforeWindow -> firstInside) with [winStart, winEnd]
        val firstInside = windowInputs.firstOrNull()
        if (prevBeforeWindow != null && firstInside != null) {
            val gap = firstInside - prevBeforeWindow!!
            if (gap > 0L && gap <= BREAK_THRESHOLD_MS) {
                val left = max(prevBeforeWindow!!, winStart)
                val right = firstInside
                if (right > left) activeMs += (right - left)
            }
        }

        // B) Closed gaps among inside events (<= threshold), left-clipped at winStart
        for (i in 0 until windowInputs.size - 1) {
            val a = windowInputs[i]
            val b = windowInputs[i + 1]
            val gap = b - a
            if (gap > 0L && gap <= BREAK_THRESHOLD_MS) {
                val left = max(a, winStart)
                val right = minOf(b, winEnd)
                if (right > left) activeMs += (right - left)
            }
        }

        // C) No right-edge tail

        return (activeMs / 1000L).toInt().coerceIn(0, WINDOW_SECONDS)
    }

    private fun updateLabel() {
        // Defensive: blank for "Do not Show"
        if (project.getUserData(com.devrhythm.IdleToolWindowFactory.STATUS_KEY) == "Do not Show") {
            blankAndReset()
            return
        }

        val dot = if (lastIsIdle) {
            "<span style='color:red'>●</span>"
        } else {
            "<span style='color:green'>●</span>"
        }

        val activeText = formatHmRounded(lastTotalActiveMs)
        val showText = cachedLast30MinText
        val newText = "<html>$dot Active Time: $activeText $showText</html>"

        if (newText != lastText) {
            label.text = newText
            lastText = newText
            statusBar?.updateWidget(WIDGET_ID)
        }
    }

    private fun blankAndReset() {
        if (label.text.isNotEmpty()) {
            label.text = ""
            lastText = ""
            inputs.clear()
            cachedLast30MinText = "( 0min of last 30 mins )"
            lastBucketIndex = -1L
        }
        this@DevRhythmStatusBarWidget.statusBar?.updateWidget(WIDGET_ID)
    }

    private fun trimOldInputs(nowMillis: Long) {
        val keepFrom = nowMillis - KEEP_MS
        while (inputs.isNotEmpty() && inputs.first() < keepFrom) {
            inputs.removeFirst()
        }
    }

    // --------- New display helpers (minutes-only, rounded) ---------

    /**
     * Format total active time (ms) as minutes-only (hours if needed), rounded to nearest minute.
     * Examples:
     *  - 7m 15s -> 7min
     *  - 7m 29s -> 7min
     *  - 7m 30s -> 8min
     *  - 7m 59s -> 8min
     *  - 1h 00m 05s -> 1h 00min
     *  - 1h 29m 40s -> 1h 30min
     */
    private fun formatHmRounded(ms: Long): String {
        val totalSec = max(0, (ms / 1000).toInt())
        val roundedMin = (totalSec + 30) / 60  // nearest minute; >=30s rounds up
        val h = roundedMin / 60
        val m = roundedMin % 60
        return if (h > 0) String.format("%dh %02dmin", h, m) else String.format("%dmin", m)
    }

    /**
     * Format a seconds count as rounded minutes (and hours if needed).
     * Used for "( … of last 30 mins )" display.
     */
    private fun formatMinutesRounded(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0min"
        val roundedMin = (totalSeconds + 30) / 60
        val h = roundedMin / 60
        val m = roundedMin % 60
        return if (h > 0) String.format("%dh %02dmin", h, m) else String.format("%dmin", m)
    }
}
