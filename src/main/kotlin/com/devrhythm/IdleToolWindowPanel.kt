package com.devrhythm

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import java.util.Locale
import kotlin.math.max

class IdleToolWindowPanel(@Suppress("UNUSED_PARAMETER") private val project: Project) : JPanel(BorderLayout()) {

    private val statusLabel     = JLabel("Current status: Idle", SwingConstants.RIGHT)
    private val activeTimeLabel = JLabel("Active project time: 00:00:00", SwingConstants.RIGHT)
    private val pctActiveLabel  = JLabel("%time active: 0.0%", SwingConstants.RIGHT)
    private val pctIdleLabel    = JLabel("%time idle: 100.0%", SwingConstants.RIGHT)

    private var lastTotalProjectMs: Long = 0L
    private var lastTotalActiveMs: Long = 0L

    private val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty()
        isOpaque = false
        arrayOf(statusLabel, activeTimeLabel, pctActiveLabel, pctIdleLabel).forEach { lbl ->
            lbl.alignmentX = RIGHT_ALIGNMENT
            lbl.maximumSize = lbl.preferredSize
            add(lbl)
        }
        maximumSize = preferredSize
    }

    private val topBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(0), JBUI.scale(0))).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(column)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(topBar, BorderLayout.NORTH) // top-right
    }

    override fun getPreferredSize(): Dimension {
        val d = topBar.preferredSize
        val ins = insets

        return Dimension(d.width + ins.left + ins.right, d.height + ins.top + ins.bottom)
    }
    override fun getMinimumSize(): Dimension = preferredSize

    fun updateSummary(totalProjectMs: Long, totalActiveMs: Long, isIdle: Boolean) {
        lastTotalProjectMs = max(0L, totalProjectMs)
        lastTotalActiveMs = max(0L, totalActiveMs)

        statusLabel.text = "Current status: " + if (isIdle) "Idle" else "Active"
        activeTimeLabel.text = "Active project time: ${formatHms(lastTotalActiveMs)}"

        arrayOf(statusLabel, activeTimeLabel).forEach { it.maximumSize = it.preferredSize }
        column.maximumSize = column.preferredSize
    }

    fun refreshPercentages() {
        val tp = lastTotalProjectMs.toDouble()
        val ta = lastTotalActiveMs.coerceAtMost(lastTotalProjectMs).toDouble()

        val pctActive = if (tp > 0.0) (ta / tp) * 100.0 else 0.0
        val pctIdle   = (100.0 - pctActive).coerceIn(0.0, 100.0)

        pctActiveLabel.text = "%time active: " + String.format(Locale.US, "%.1f%%", pctActive)
        pctIdleLabel.text   = "%time idle: "   + String.format(Locale.US, "%.1f%%", pctIdle)

        arrayOf(pctActiveLabel, pctIdleLabel).forEach { it.maximumSize = it.preferredSize }
        column.maximumSize = column.preferredSize
        revalidate()
        repaint()
    }

    private fun formatHms(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
