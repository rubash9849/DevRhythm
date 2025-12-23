package com.devrhythm

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.concurrent.fixedRateTimer
import kotlin.math.max
import kotlin.math.min

/**
 * Startup orchestrator for timers, input capture, CSV writes, and welcome balloon.
 * Uses a 5-minute idle threshold everywhere.
 */
class IdleTracker : StartupActivity, StartupActivity.DumbAware {

    companion object {
        private val WELCOME_SEEN_KEY: Key<Boolean> = Key.create("DevRhythm.WelcomeSeen")
        private val SESSION_START_INSTANT_KEY: Key<Instant> = Key.create("DevRhythm.SessionStartInstant")
        private val OPEN_BALLOON_KEY: Key<Balloon> = Key.create("DevRhythm.OpenBalloonRef")
        private val BALLOON_CLOSED_EXPLICITLY_KEY: Key<Boolean> = Key.create("DevRhythm.BalloonClosedExplicitly")

        val FIRST_INPUT_SEEN_KEY: Key<Boolean> = Key.create("DevRhythm.FirstInputSeen")
        val SESSION_ARMED_KEY: Key<Boolean> = Key.create("DevRhythm.SessionArmed")

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        private val TIME_FMT_MICROS = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault())

        private const val IDLE_THRESHOLD_NS: Long = 300_000_000_000L // 5 min in ns
    }

    override fun runActivity(project: Project) {
        val disabled = AtomicBoolean(false)
        var disabledNoticeTimer: Timer? = null

        project.putUserData(FIRST_INPUT_SEEN_KEY, false)
        project.putUserData(SESSION_ARMED_KEY, false)

        fun startDisabledNoticesEvery30s() {
            if (disabledNoticeTimer != null) return
            val timer = Timer("DevRhythmDisabled-${project.name}", true)
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) != true) return
                    if (project.getUserData(IdleToolWindowFactory.SUPPRESS_WARNINGS_SESSION_KEY) == true) return
                    runCatching { showDisabledWarningWithAction(project) }
                }
            }, 30_000L, 30_000L)
            disabledNoticeTimer = timer
        }

        fun markDisabled(reason: String) {
            if (!disabled.compareAndSet(false, true)) return
            project.putUserData(IdleToolWindowFactory.DISABLED_KEY, true)
            runCatching { ToolWindowManager.getInstance(project).getToolWindow("DevRhythm")?.hide(null) }
            runCatching { showDisabledWarningWithAction(project, reason) }
            project.getUserData(OPEN_BALLOON_KEY)?.let { b ->
                runCatching { b.hide() }
                project.putUserData(OPEN_BALLOON_KEY, null)
            }
            startDisabledNoticesEvery30s()
        }

        val appStartInstant = Instant.now()
        val appStartEpochMicros = epochMicros(appStartInstant)
        val appStartNano = System.nanoTime()

        val userName = System.getProperty("user.name")
        val formattedStartDate = DATE_FMT.format(appStartInstant)
        val formattedStartTimeMicros = TIME_FMT_MICROS.format(appStartInstant)

        project.putUserData(SESSION_START_INSTANT_KEY, appStartInstant)

        ensureProjectStatusCsvRandom(project, userName, project.name)
        val projectStatus = readProjectStatus(project, userName)
        project.putUserData(IdleToolWindowFactory.STATUS_KEY, projectStatus)
        project.putUserData(IdleToolWindowFactory.SESSION_START_MS_KEY, appStartEpochMicros)

        // ✅ FIXED: No more internal StatusBar.removeWidget() calls
        // Widget visibility is controlled by DevRhythmStatusBarWidgetFactory.isAvailable()

        maybeShowWelcomeOnce(project, projectStatus, userName, formattedStartDate, formattedStartTimeMicros, appStartEpochMicros)

        val csvFile = resolveCsv(project, userName)
        val historicalTotalMicros = sumCsvSecondsAsMicros(csvFile, listOf("Total Time (sec)"))
        val historicalActiveMicros = sumCsvSecondsAsMicros(csvFile, listOf("Active Time (sec)", "Total Active Time (sec)"))

        var lastInputNano = appStartNano
        var inputCount = 0

        var totalActiveNanos = 0L
        var currentSessionNanos = 0L
        var totalBreakNanos = 0L

        var sessionCount = 0
        var maxSessionNanos = 0L
        var minSessionNanos = Long.MAX_VALUE

        var breakCount = 0
        var maxBreakNanos = 0L
        var minBreakNanos = Long.MAX_VALUE

        fun window(): IdleToolWindowPanel? = project.getUserData(IdleToolWindowFactory.PANEL_KEY)

        fun belongsToThisProject(comp: Component?): Boolean {
            try {
                if (comp != null) {
                    val dc = DataManager.getInstance().getDataContext(comp)
                    CommonDataKeys.PROJECT.getData(dc)?.let { return it == project }
                }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { fo ->
                    val dc2 = DataManager.getInstance().getDataContext(fo)
                    CommonDataKeys.PROJECT.getData(dc2)?.let { return it == project }
                }
            } catch (_: Exception) { }
            return false
        }

        fun pushTotalsNow(nowNano: Long) {
            if (disabled.get()) return

            val firstSeen = (project.getUserData(FIRST_INPUT_SEEN_KEY) == true)
            val armed = (project.getUserData(SESSION_ARMED_KEY) == true)

            val isIdleNow = if (!firstSeen) true else nowNano - lastInputNano >= IDLE_THRESHOLD_NS
            val totalProjectMicros = historicalTotalMicros + ((nowNano - appStartNano) / 1_000L)
            val liveActiveMicros = if (armed) (totalActiveNanos + currentSessionNanos) else 0L
            val totalActiveMicros = historicalActiveMicros + (liveActiveMicros / 1_000L)

            SwingUtilities.invokeLater {
                window()?.updateSummary(
                    totalProjectMs = totalProjectMicros / 1_000L,
                    totalActiveMs = totalActiveMicros / 1_000L,
                    isIdle = isIdleNow
                )
                runCatching {
                    project.messageBus
                        .syncPublisher(DevRhythmEvents.TOPIC)
                        .onTick(totalProjectMicros / 1_000L, totalActiveMicros / 1_000L, isIdleNow)
                }
            }
        }

        val updateTimer = fixedRateTimer("DevRhythm.updatePanel", daemon = true, initialDelay = 0, period = 1000) {
            pushTotalsNow(System.nanoTime())
        }

        fun handleInput(nowNano: Long) {
            if (disabled.get()) return
            inputCount++

            runCatching {
                project.messageBus
                    .syncPublisher(DevRhythmInputEvents.TOPIC)
                    .onInput(System.currentTimeMillis())
            }

            when (inputCount) {
                1 -> {
                    project.putUserData(FIRST_INPUT_SEEN_KEY, true)
                    lastInputNano = nowNano
                    pushTotalsNow(nowNano)
                    SwingUtilities.invokeLater { window()?.refreshPercentages() }
                    return
                }
                2 -> {
                    val gapNs = nowNano - lastInputNano
                    if (gapNs < IDLE_THRESHOLD_NS) {
                        currentSessionNanos += gapNs
                    } else {
                        currentSessionNanos = 0L
                    }
                    project.putUserData(SESSION_ARMED_KEY, true)
                    lastInputNano = nowNano
                    pushTotalsNow(nowNano)
                    SwingUtilities.invokeLater { window()?.refreshPercentages() }
                    return
                }
            }

            val gapNs = nowNano - lastInputNano
            if (gapNs >= IDLE_THRESHOLD_NS) {
                breakCount++
                totalBreakNanos += gapNs
                maxBreakNanos = max(maxBreakNanos, gapNs)
                minBreakNanos = if (minBreakNanos == Long.MAX_VALUE) gapNs else min(minBreakNanos, gapNs)
                if (currentSessionNanos > 0) {
                    totalActiveNanos += currentSessionNanos
                    sessionCount++
                    maxSessionNanos = max(maxSessionNanos, currentSessionNanos)
                    minSessionNanos =
                        if (minSessionNanos == Long.MAX_VALUE) currentSessionNanos else min(minSessionNanos, currentSessionNanos)
                }
                currentSessionNanos = 0L
            } else {
                currentSessionNanos += gapNs
            }
            lastInputNano = nowNano
            pushTotalsNow(nowNano)
            SwingUtilities.invokeLater { window()?.refreshPercentages() }
        }

        IdeEventQueue.getInstance().addDispatcher({ e ->
            when (e) {
                is KeyEvent -> {
                    if (disabled.get()) return@addDispatcher false
                    if (!belongsToThisProject(e.component)) return@addDispatcher false
                    when (e.id) {
                        KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED -> handleInput(System.nanoTime())
                    }
                }
                is MouseEvent -> {
                    if (disabled.get()) return@addDispatcher false
                    if (!belongsToThisProject(e.component)) return@addDispatcher false
                    when (e.id) {
                        MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED -> handleInput(System.nanoTime())
                    }
                }
                is MouseWheelEvent -> { /* ignore */ }
            }
            false
        }, project)

        fun isSameFrameMultiProject(): Boolean {
            val wm = WindowManager.getInstance()
            val myFrame = wm.getFrame(project) ?: return false
            val pm = ProjectManager.getInstance()
            var count = 0
            for (p in pm.openProjects) {
                val f = wm.getFrame(p)
                if (f === myFrame) {
                    count++
                    if (count >= 2) return true
                }
            }
            return false
        }

        fun hasMultipleIdeaRoots(): Boolean {
            val fs = LocalFileSystem.getInstance()
            val roots: Array<VirtualFile> = ProjectRootManager.getInstance(project).contentRoots
            val ideaRootCount = roots.mapNotNull { root ->
                root.findChild(".idea")?.takeIf { it.isDirectory }?.parent
                    ?: fs.findFileByPath("${root.path}/.idea")?.takeIf { it.isDirectory }?.parent
            }.map { it.path }.distinct().count()
            return ideaRootCount >= 2
        }

        fun checkAndDisableIfAttached() {
            if (disabled.get()) return
            if (isSameFrameMultiProject() || hasMultipleIdeaRoots()) {
                runCatching { updateTimer.cancel() }
                runCatching { updateTimer.purge() }
                writeFinalRow(
                    Instant.now(), userName, formattedStartDate, formattedStartTimeMicros, appStartEpochMicros, appStartNano,
                    totalActiveNanos, currentSessionNanos, totalBreakNanos,
                    sessionCount, maxSessionNanos, minSessionNanos, breakCount, maxBreakNanos, minBreakNanos, csvFile,
                    project.name
                )
                markDisabled("Multiple projects are attached in this window. Stats were saved up to the attach moment; further tracking is disabled.")
            }
        }

        ApplicationManager.getApplication().invokeLater { checkAndDisableIfAttached() }

        val appConn = ApplicationManager.getApplication().messageBus.connect(project)
        appConn.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectOpened(project2: Project) = checkAndDisableIfAttached()
            override fun projectClosed(project2: Project) = checkAndDisableIfAttached()
        })
        project.messageBus.connect(project).subscribe(
            com.intellij.ProjectTopics.PROJECT_ROOTS,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = checkAndDisableIfAttached()
            }
        )

        val disposable = Disposable {
            disabledNoticeTimer?.cancel()
            disabledNoticeTimer = null
            if (disabled.get()) return@Disposable
            writeFinalRow(
                Instant.now(), userName, formattedStartDate, formattedStartTimeMicros, appStartEpochMicros, appStartNano,
                totalActiveNanos, currentSessionNanos, totalBreakNanos,
                sessionCount, maxSessionNanos, minSessionNanos, breakCount, maxBreakNanos, minBreakNanos, csvFile,
                project.name
            )
        }
        Disposer.register(project, disposable)
    }

    private fun maybeShowWelcomeOnce(
        project: Project,
        projectStatus: String?,
        userName: String,
        sessionStartDate: String,
        sessionStartTimeMicros: String,
        sessionStartEpochMicros: Long
    ) {
        if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) == true) return
        if (projectStatus != "Show") return
        if (project.getUserData(WELCOME_SEEN_KEY) == true) return

        val popupCount = countPopupCloseRows(project, userName)
        if (popupCount >= 2) return

        ApplicationManager.getApplication().invokeLater {
            if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) == true) return@invokeLater
            tryShowWelcomeWhenWidgetReady(
                project = project,
                userName = userName,
                sessionStartDate = sessionStartDate,
                sessionStartTimeMicros = sessionStartTimeMicros,
                sessionStartEpochMicros = sessionStartEpochMicros,
                existingRowsCount = popupCount,
                attempt = 0
            )
        }
    }

    // ✅ FIXED: No internal StatusBar API usage - using component-based approach
    private fun tryShowWelcomeWhenWidgetReady(
        project: Project,
        userName: String,
        sessionStartDate: String,
        sessionStartTimeMicros: String,
        sessionStartEpochMicros: Long,
        existingRowsCount: Int,
        attempt: Int
    ) {
        if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) == true) return

        // ✅ FIXED: Use public WindowManager API instead of StatusBar internal APIs
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null) {
            if (showWelcomeBalloonAtFrame(
                    project, userName, sessionStartDate, sessionStartTimeMicros, sessionStartEpochMicros, existingRowsCount, frame
                )
            ) {
                project.putUserData(WELCOME_SEEN_KEY, true)
            }
            return
        }

        if (attempt >= 10) return
        javax.swing.Timer(300) {
            tryShowWelcomeWhenWidgetReady(
                project, userName, sessionStartDate, sessionStartTimeMicros, sessionStartEpochMicros, existingRowsCount, attempt + 1
            )
        }.apply { isRepeats = false; start() }
    }

    // ✅ FIXED: No internal StatusBar API usage - shows balloon just above status bar
    private fun showWelcomeBalloonAtFrame(
        project: Project,
        userName: String,
        sessionStartDate: String,
        sessionStartTimeMicros: String,
        sessionStartEpochMicros: Long,
        existingRowsCount: Int,
        frame: java.awt.Component
    ): Boolean {
        try {
            if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) == true) return false

            val popupLife = 3 - existingRowsCount

            val panel = javax.swing.JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16)
                isOpaque = false
            }

            val title = javax.swing.JLabel("Welcome to DevRhythm").apply {
                font = font.deriveFont((font.style or java.awt.Font.BOLD), 18f)
                alignmentX = java.awt.Component.CENTER_ALIGNMENT
            }
            panel.add(title)
            panel.add(javax.swing.Box.createVerticalStrut(8))

            val statusRow = javax.swing.JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = java.awt.Component.CENTER_ALIGNMENT
            }
            val statusHdr = javax.swing.JLabel("Status").apply {
                font = font.deriveFont((font.style or java.awt.Font.BOLD), 14f)
            }
            val redDot = javax.swing.JLabel("idle").apply {
                font = font.deriveFont(14f)
                icon = CircleDotIcon(java.awt.Color(0xE0, 0x31, 0x31), 14)
            }
            val greenDot = javax.swing.JLabel("active").apply {
                font = font.deriveFont(14f)
                icon = CircleDotIcon(java.awt.Color(0x2F, 0x9E, 0x44), 14)
            }
            statusRow.add(statusHdr)
            statusRow.add(javax.swing.Box.createHorizontalStrut(10))
            statusRow.add(redDot as Component)
            statusRow.add(javax.swing.Box.createHorizontalStrut(18))
            statusRow.add(greenDot as Component)
            panel.add(statusRow)

            panel.add(javax.swing.Box.createVerticalStrut(8))
            val desc = javax.swing.JLabel(
                "<html>Shows your <b>total active time</b> and your <b>active time in last 30 mins</b> <i>(refreshes per minute)</i>.<br><br>" +
                        "Activity is measured with keystrokes and mouse clicks <br>" +
                        "See the DevRhythm Status Bar below<br></html>"            ).apply {
                font = font.deriveFont(14f)
                alignmentX = java.awt.Component.CENTER_ALIGNMENT
            }
            panel.add(desc)

            val arrowsPanel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                alignmentX = java.awt.Component.CENTER_ALIGNMENT
            }
            val arrows = javax.swing.JLabel("⬇⬇⬇").apply {
                font = font.deriveFont(64f)
                alignmentX = java.awt.Component.CENTER_ALIGNMENT
            }
            arrowsPanel.add(arrows)
            panel.add(javax.swing.Box.createVerticalStrut(6))
            panel.add(arrowsPanel)

            val builder = JBPopupFactory.getInstance()
                .createBalloonBuilder(panel)
                .setFillColor(JBColor.PanelBackground)
                .setHideOnClickOutside(false)
                .setHideOnKeyOutside(false)
                .setHideOnAction(false)
                .setHideOnFrameResize(false)
                .setCloseButtonEnabled(true)
                .setAnimationCycle(150)
                .setBorderColor(JBColor(0x7A8CA3, 0x7A8CA3))
                .setBorderInsets(java.awt.Insets(8, 10, 8, 10))

            val balloon = builder.createBalloon()
            // ✅ FIXED: Proper disposal with project as parent
            Disposer.register(project, balloon)

            // Track if balloon was closed explicitly (via close button)
            project.putUserData(BALLOON_CLOSED_EXPLICITLY_KEY, false)

            var wasExplicitlyClosed = false

            // ✅ Auto-close after 5 minutes (300,000 ms) - no logging for auto-close
            val autoCloseTimer = javax.swing.Timer(300000) {
                if (!balloon.isDisposed && !wasExplicitlyClosed) {
                    // Auto-close - don't log this
                    balloon.hide()
                    project.putUserData(OPEN_BALLOON_KEY, null)
                }
            }.apply { isRepeats = false; start() }

            // Simple approach: assume any hide within first few seconds is explicit close
            // (users don't auto-close immediately, but close button clicks happen quickly)
            val explicitCloseTimer = javax.swing.Timer(5000) {
                // After 5 seconds, any close is considered explicit
                wasExplicitlyClosed = true
            }.apply { isRepeats = false; start() }

            // Monitor balloon state
            val balloonMonitorTimer = javax.swing.Timer(100) {
                if (balloon.isDisposed) {
                    if (wasExplicitlyClosed) {
                        // Log explicit close
                        logBalloonClose(
                            project, userName, sessionStartDate, sessionStartTimeMicros,
                            sessionStartEpochMicros, popupLife
                        )
                    }
                    // Stop all timers
                    autoCloseTimer.stop()
                    explicitCloseTimer.stop()
                    (it.source as javax.swing.Timer).stop()
                }
            }.apply { isRepeats = true; start() }

            // ✅ FIXED: Show balloon just above the status bar
            // Position at bottom-right corner, just above status bar area
            val point = RelativePoint(frame, Point(frame.width - 400, frame.height - 80))
            balloon.show(point, Balloon.Position.above)
            project.putUserData(OPEN_BALLOON_KEY, balloon)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun logBalloonClose(
        project: Project,
        userName: String,
        sessionStartDate: String,
        sessionStartTimeMicros: String,
        sessionStartEpochMicros: Long,
        popupLife: Int
    ) {
        val closeInstant = Instant.now()
        appendPopupCloseRow(
            project = project,
            userName = userName,
            projectName = project.name,
            sessionStartDate = sessionStartDate,
            sessionStartTimeMicros = sessionStartTimeMicros,
            sessionStartEpochMicros = sessionStartEpochMicros,
            closeDate = DATE_FMT.format(closeInstant),
            closeTimeMicros = TIME_FMT_MICROS.format(closeInstant),
            closeEpochMicros = epochMicros(closeInstant),
            popupLife = popupLife
        )
        project.putUserData(OPEN_BALLOON_KEY, null)
    }

    private fun projectDataDir(project: Project): File {
        val base = project.basePath ?: System.getProperty("user.home")
        val dir = File(base, ".devrhythm")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun ensureProjectStatusCsvRandom(project: Project, userName: String, projectName: String) {
        val baseDir = projectDataDir(project)
        val file = File(baseDir, "${userName}_${projectName}_status.csv")
        if (file.exists()) return
        runCatching {
            file.parentFile?.mkdirs()
            val r = try { SecureRandom.getInstanceStrong().nextDouble() } catch (_: Exception) { SecureRandom().nextDouble() }
            val status = if (r < 0.5) "Show" else "Do not Show"
            val now = Instant.now()
            val epochUs = epochMicros(now)
            FileWriter(file, false).use { w ->
                w.write("Username,Project,Date,Time,Epoch (us),Random,Status\n")
                w.write("$userName,$projectName,${DATE_FMT.format(now)},${TIME_FMT_MICROS.format(now)},$epochUs,${String.format(Locale.US, "%.6f", r)},$status\n")
            }
        }
    }

    private fun readProjectStatus(project: Project, userName: String): String? {
        val baseDir = projectDataDir(project)
        val file = File(baseDir, "${userName}_${project.name}_status.csv")
        if (!file.exists()) return null
        return try {
            BufferedReader(FileReader(file)).use { br ->
                br.readLine() ?: return null
                val row = br.readLine() ?: return null
                row.split(",").last().trim()
            }
        } catch (_: Exception) { null }
    }

    private fun writeFinalRow(
        endInstant: Instant,
        userName: String,
        formattedStartDate: String,
        formattedStartTimeMicros: String,
        appStartEpochMicros: Long,
        appStartNano: Long,
        totalActiveNanos: Long,
        currentSessionNanos: Long,
        totalBreakNanos: Long,
        sessionCount: Int,
        maxSessionNanos: Long,
        minSessionNanos: Long,
        breakCount: Int,
        maxBreakNanos: Long,
        minBreakNanos: Long,
        csvFile: File,
        projectName: String
    ) {
        runCatching {
            val endEpochMicros = epochMicros(endInstant)
            val endDate = DATE_FMT.format(endInstant)
            val endTimeMicros = TIME_FMT_MICROS.format(endInstant)

            val nowNano = System.nanoTime()
            val totalTimeMicros = (nowNano - appStartNano) / 1_000L
            val liveActiveMicros = (totalActiveNanos + currentSessionNanos) / 1_000L
            val breakMicros = totalBreakNanos / 1_000L

            val ongoingSessionCount = if (currentSessionNanos > 0) 1 else 0
            val allSessions = sessionCount + ongoingSessionCount
            val avgSessionMicros = if (allSessions > 0) (liveActiveMicros / allSessions) else 0L

            val maxSessionMicros = when {
                sessionCount == 0 -> currentSessionNanos / 1_000L
                else -> max(maxSessionNanos, currentSessionNanos) / 1_000L
            }
            val minSessionMicros = when {
                sessionCount == 0 -> currentSessionNanos / 1_000L
                else -> {
                    val closedMin = if (minSessionNanos == Long.MAX_VALUE) Long.MAX_VALUE else minSessionNanos
                    val chosenNs = if (closedMin == Long.MAX_VALUE) currentSessionNanos
                    else if (currentSessionNanos == 0L) closedMin
                    else min(closedMin, currentSessionNanos)
                    chosenNs / 1_000L
                }
            }
            val avgBreakMicros = if (breakCount > 0) (breakMicros / breakCount) else 0L
            val maxBreakMicros = maxBreakNanos / 1_000L
            val minBreakMicros = if (minBreakNanos == Long.MAX_VALUE) 0L else minBreakNanos / 1_000L

            val writeHeader = !csvFile.exists()
            FileWriter(csvFile, true).use { writer ->
                if (writeHeader) {
                    writer.write(
                        "Username,Project,Start Date,Start Time,Start Epoch (us),End Date,End Time,End Epoch (us),Total Time (sec),Active Time (sec),Break Time (sec),Total Mini Sessions," +
                                "Avg Mini Session (sec),Avg Break (sec),Max Mini Session (sec),Min Mini Session (sec),Max Break (sec),Min Break (sec)\n"
                    )
                }
                fun microsToSec2(us: Long): String =
                    BigDecimal(us).divide(BigDecimal(1_000_000L), 2, RoundingMode.HALF_UP).toPlainString()

                val line = buildString {
                    append(userName).append(',')
                    append(projectName).append(',')
                    append(formattedStartDate).append(',')
                    append(formattedStartTimeMicros).append(',')
                    append(appStartEpochMicros).append(',')
                    append(endDate).append(',')
                    append(endTimeMicros).append(',')
                    append(endEpochMicros).append(',')
                    append(microsToSec2(totalTimeMicros)).append(',')
                    append(microsToSec2(liveActiveMicros)).append(',')
                    append(microsToSec2(breakMicros)).append(',')
                    append(allSessions).append(',')
                    append(microsToSec2(avgSessionMicros)).append(',')
                    append(microsToSec2(avgBreakMicros)).append(',')
                    append(microsToSec2(maxSessionMicros)).append(',')
                    append(microsToSec2(minSessionMicros)).append(',')
                    append(microsToSec2(maxBreakMicros)).append(',')
                    append(microsToSec2(minBreakMicros)).append('\n')
                }
                writer.write(line)
            }
        }
    }

    private fun epochMicros(instant: Instant): Long =
        instant.epochSecond * 1_000_000L + (instant.nano / 1_000L)

    private fun resolveCsv(project: Project, userName: String): File {
        val dir = projectDataDir(project)
        val file = File(dir, "${userName}_${project.name}_idle_stats.csv")
        file.parentFile?.mkdirs()
        return file
    }

    private fun sumCsvSecondsAsMicros(csv: File, headerCandidates: List<String>): Long {
        if (!csv.exists()) return 0L
        return try {
            BufferedReader(FileReader(csv)).use { br ->
                val header = br.readLine() ?: return 0L
                val cols = header.split(",").map { it.trim().lowercase(Locale.US) }
                val idx = headerCandidates.map { it.lowercase(Locale.US) }
                    .map { cols.indexOf(it) }
                    .firstOrNull { it >= 0 } ?: return 0L

                var sumMicros = BigDecimal.ZERO
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val parts = line!!.split(",").map { it.trim().trim('"') }
                    if (idx in parts.indices) {
                        val sec = parts[idx].toBigDecimalOrNull() ?: BigDecimal.ZERO
                        sumMicros = sumMicros.add(sec.multiply(BigDecimal(1_000_000L)))
                    }
                }
                sumMicros.setScale(0, RoundingMode.HALF_UP).longValueExact()
            }
        } catch (_: Exception) { 0L }
    }

    private fun popupCloseCsv(project: Project, userName: String): File {
        val dir = projectDataDir(project)
        return File(dir, "${userName}_${project.name}_popup_close.csv").apply { parentFile?.mkdirs() }
    }

    private fun countPopupCloseRows(project: Project, userName: String): Int {
        val file = popupCloseCsv(project, userName)
        if (!file.exists()) return 0
        return try {
            BufferedReader(FileReader(file)).use { br ->
                var count = 0
                val first = br.readLine()
                if (first != null && first.startsWith("Username,Project,")) {
                    // header present
                } else if (first != null) {
                    count++
                }
                while (br.readLine() != null) count++
                count
            }
        } catch (_: Exception) { 0 }
    }

    private fun appendPopupCloseRow(
        project: Project,
        userName: String,
        projectName: String,
        sessionStartDate: String,
        sessionStartTimeMicros: String,
        sessionStartEpochMicros: Long,
        closeDate: String,
        closeTimeMicros: String,
        closeEpochMicros: Long,
        popupLife: Int
    ) {
        val file = popupCloseCsv(project, userName)
        runCatching {
            val writeHeader = !file.exists()
            FileWriter(file, true).use { w ->
                if (writeHeader) {
                    w.write(
                        "Username,Project,Session Start Date,Session Start Time,Session Start Epoch (us),Popup Close Date,Popup Close Time,Popup Close Epoch (us),popup_life\n"
                    )
                }
                w.write(
                    listOf(
                        userName,
                        projectName,
                        sessionStartDate,
                        sessionStartTimeMicros,
                        sessionStartEpochMicros.toString(),
                        closeDate,
                        closeTimeMicros,
                        closeEpochMicros.toString(),
                        popupLife.toString()
                    ).joinToString(separator = ",", postfix = "\n")
                )
            }
        }
    }

    private class CircleDotIcon(
        private val color: java.awt.Color,
        private val size: Int = 14
    ) : javax.swing.Icon {
        override fun getIconWidth() = size
        override fun getIconHeight() = size
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )
                g2.color = color
                g2.fillOval(x, y, size, size)
            } finally { g2.dispose() }
        }
    }

    private fun showDisabledWarningWithAction(project: Project, extraReason: String? = null) {
        if (project.getUserData(IdleToolWindowFactory.SUPPRESS_WARNINGS_SESSION_KEY) == true) return
        val body = buildString {
            if (!extraReason.isNullOrBlank()) {
                append(extraReason).append("\n")
            }
            append("Please work in a <b>detached</b> project window. Stats are not being tracked or written.")
        }
        val n = NotificationGroupManager.getInstance()
            .getNotificationGroup("DevRhythm")
            .createNotification("DevRhythm is disabled", body, NotificationType.WARNING)

        n.addAction(
            NotificationAction.createSimpleExpiring("Don't show again (this session)") {
                project.putUserData(IdleToolWindowFactory.SUPPRESS_WARNINGS_SESSION_KEY, true)
            }
        )
        n.notify(project)
    }
}