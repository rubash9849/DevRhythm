package com.devrhythm

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingUtilities
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Timer
import java.util.TimerTask

class IdleToolWindowFactory : ToolWindowFactory {

    companion object {
        private const val TW_ID = "DevRhythm"

        // Global hard switch: NEVER show panel/tool window anywhere (kept false = disabled UI path).
        private const val UI_ENABLED = false

        val PANEL_KEY: Key<IdleToolWindowPanel> = Key.create("IdleTracker.Panel")
        val STATUS_KEY: Key<String> = Key.create("DevRhythm.Status")
        val SESSION_START_MS_KEY: Key<Long> = Key.create("DevRhythm.SessionStartMs")
        private val LAST_VISIBLE_KEY: Key<Boolean> = Key.create("DevRhythm.LastVisible")

        val DISABLED_KEY: Key<Boolean> = Key.create("DevRhythm.Disabled")
        private val REMINDER_TIMER_KEY: Key<Timer> = Key.create("DevRhythm.ReminderTimer")
        private val SUPPRESS_CLOSE_LOG_KEY: Key<Boolean> = Key.create("DevRhythm.SuppressCloseLogOnce")

        private val AUTO_SHOW_PENDING_KEY: Key<Boolean> = Key.create("DevRhythm.AutoShowPending")
        private val FIRST_OPEN_LOGGED_KEY: Key<Boolean> = Key.create("DevRhythm.FirstOpenLogged")

        private val LAST_ACTION_KEY: Key<String> = Key.create("DevRhythm.LastAction")
        private val ENFORCE_HIDE_ON_STARTUP_KEY: Key<Boolean> = Key.create("DevRhythm.EnforceHideOnce")

        val SUPPRESS_WARNINGS_SESSION_KEY: Key<Boolean> =
            Key.create("DevRhythm.SuppressWarningsThisSession")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Always ensure there is NO visible panel/tool window, regardless of status.
        project.putUserData(PANEL_KEY, null)
        project.putUserData(LAST_VISIBLE_KEY, false)
        project.putUserData(LAST_ACTION_KEY, "Close")
        project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)

        // Remove any content and hide immediately
        runCatching {
            toolWindow.contentManager.removeAllContents(true)
            toolWindow.hide(null)
        }

        // If UI is disabled, do not create panel, listeners, or hooks.
        if (!UI_ENABLED) return

        val panel = IdleToolWindowPanel(project)
        project.putUserData(PANEL_KEY, panel)

        val content = ContentFactory.getInstance().createContent(panel, "", false).apply {
            try { setTabName("") } catch (_: Throwable) {}
            setDisplayName("")
        }
        toolWindow.contentManager.addContent(content)

        // Baseline state
        project.putUserData(LAST_VISIBLE_KEY, false)
        project.putUserData(LAST_ACTION_KEY, "Close")
        project.putUserData(SUPPRESS_CLOSE_LOG_KEY, false)

        installToolWindowListeners(project, toolWindow, panel)
        installProjectCloseHook(project, toolWindow)

        val status = project.getUserData(STATUS_KEY) ?: readStatusFromCsv(project)

        if (status == "Show") {
            project.putUserData(AUTO_SHOW_PENDING_KEY, true)
            project.putUserData(FIRST_OPEN_LOGGED_KEY, false)
            SwingUtilities.invokeLater { toolWindow.show(null) }
        } else {
            project.putUserData(ENFORCE_HIDE_ON_STARTUP_KEY, true)
            SwingUtilities.invokeLater {
                if (toolWindow.isVisible && project.getUserData(ENFORCE_HIDE_ON_STARTUP_KEY) == true) {
                    project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                    runCatching { toolWindow.hide(null) }
                    project.putUserData(LAST_VISIBLE_KEY, false)
                    project.putUserData(ENFORCE_HIDE_ON_STARTUP_KEY, false)
                    stopWarning(project)
                }
            }
            val t = javax.swing.Timer(160) {
                if (toolWindow.isVisible && project.getUserData(ENFORCE_HIDE_ON_STARTUP_KEY) == true) {
                    project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                    runCatching { toolWindow.hide(null) }
                    project.putUserData(LAST_VISIBLE_KEY, false)
                    project.putUserData(ENFORCE_HIDE_ON_STARTUP_KEY, false)
                    stopWarning(project)
                }
            }
            t.isRepeats = false
            t.start()
        }

        if (project.getUserData(DISABLED_KEY) == true) {
            startWarningEvery30s(project)
        }
    }

    private fun installToolWindowListeners(project: Project, toolWindow: ToolWindow, panel: IdleToolWindowPanel) {
        val connection = project.messageBus.connect(project)
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id != TW_ID) return

                    val status = project.getUserData(STATUS_KEY) ?: readStatusFromCsv(project)
                    if (status == "Do not Show" && project.getUserData(ENFORCE_HIDE_ON_STARTUP_KEY) == true) {
                        project.putUserData(ENFORCE_HIDE_ON_STARTUP_KEY, false)
                        project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                        runCatching { tw.hide(null) }
                        project.putUserData(LAST_VISIBLE_KEY, false)
                        stopWarning(project)
                        return
                    }

                    if (project.getUserData(LAST_VISIBLE_KEY) == true) return

                    // ✅ Use public API for positioning
                    positionToolWindow(tw)

                    if (project.getUserData(DISABLED_KEY) == true) {
                        startWarningEvery30s(project)
                        showDisabledNotice(project)
                    }

                    val autoPending = project.getUserData(AUTO_SHOW_PENDING_KEY) == true
                    val firstOpenLogged = project.getUserData(FIRST_OPEN_LOGGED_KEY) == true
                    val actionType = when {
                        status == "Show" && autoPending && !firstOpenLogged -> "Auto"
                        else -> "Manual"
                    }
                    project.putUserData(AUTO_SHOW_PENDING_KEY, false)
                    if (actionType == "Auto") project.putUserData(FIRST_OPEN_LOGGED_KEY, true)

                    project.putUserData(SUPPRESS_CLOSE_LOG_KEY, false)
                    project.putUserData(LAST_VISIBLE_KEY, true)
                }

                override fun stateChanged(manager: ToolWindowManager) {
                    val tw = manager.getToolWindow(TW_ID) ?: return
                    val nowVisible = tw.isVisible
                    val wasVisible = project.getUserData(LAST_VISIBLE_KEY) == true

                    val status = project.getUserData(STATUS_KEY) ?: readStatusFromCsv(project)
                    if (status == "Do not Show" && project.getUserData(ENFORCE_HIDE_ON_STARTUP_KEY) == true && nowVisible) {
                        project.putUserData(ENFORCE_HIDE_ON_STARTUP_KEY, false)
                        project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                        runCatching { tw.hide(null) }
                        project.putUserData(LAST_VISIBLE_KEY, false)
                        stopWarning(project)
                        return
                    }

                    if (nowVisible == wasVisible) return

                    if (!nowVisible && wasVisible) {
                        if (project.getUserData(SUPPRESS_CLOSE_LOG_KEY) == true) {
                            project.putUserData(SUPPRESS_CLOSE_LOG_KEY, false)
                        }
                        stopWarning(project)
                        project.putUserData(LAST_VISIBLE_KEY, false)
                        return
                    }

                    if (nowVisible && !wasVisible) {
                        // ✅ Use public API for positioning
                        positionToolWindow(tw)

                        val autoPending = project.getUserData(AUTO_SHOW_PENDING_KEY) == true
                        val firstOpenLogged = project.getUserData(FIRST_OPEN_LOGGED_KEY) == true
                        val actionType = when {
                            status == "Show" && autoPending && !firstOpenLogged -> "Auto"
                            else -> "Manual"
                        }
                        project.putUserData(AUTO_SHOW_PENDING_KEY, false)
                        if (actionType == "Auto") project.putUserData(FIRST_OPEN_LOGGED_KEY, true)

                        project.putUserData(SUPPRESS_CLOSE_LOG_KEY, false)
                        project.putUserData(LAST_VISIBLE_KEY, true)

                        if (project.getUserData(DISABLED_KEY) == true) {
                            startWarningEvery30s(project)
                        }
                    }
                }
            }
        )
    }

    // ✅ PUBLIC API ONLY: Basic tool window positioning
    private fun positionToolWindow(toolWindow: ToolWindow) {
        runCatching {
            // Use public API to position tool window
            toolWindow.setAnchor(com.intellij.openapi.wm.ToolWindowAnchor.RIGHT, null)
        }

        // Let IDE handle default positioning
        SwingUtilities.invokeLater {
            // Tool window will appear in standard IDE location
        }
    }

    /** On project close: if panel is open, auto-close it WITHOUT logging. */
    private fun installProjectCloseHook(project: Project, toolWindow: ToolWindow) {
        val appConn = ApplicationManager.getApplication().messageBus.connect(project)
        appConn.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosing(project2: Project) {
                if (project2 != project) return
                if (toolWindow.isVisible) {
                    project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                    try { toolWindow.hide(null) } catch (_: Throwable) {}
                    project.putUserData(LAST_VISIBLE_KEY, false)
                    project.putUserData(LAST_ACTION_KEY, "Close")
                    stopWarning(project)
                }
            }
            override fun projectClosed(project2: Project) {
                if (project2 != project) return
                if (toolWindow.isVisible) {
                    project.putUserData(SUPPRESS_CLOSE_LOG_KEY, true)
                    try { toolWindow.hide(null) } catch (_: Throwable) {}
                    project.putUserData(LAST_VISIBLE_KEY, false)
                    project.putUserData(LAST_ACTION_KEY, "Close")
                    stopWarning(project)
                }
            }
        })
    }

    fun startWarningEvery30s(project: Project) {
        if (project.getUserData(REMINDER_TIMER_KEY) != null) return
        val timer = Timer("DevRhythmWarning-${project.name}", true)
        timer.schedule(object : TimerTask() {
            override fun run() {
                val disabled = project.getUserData(DISABLED_KEY) == true
                if (!disabled) return
                if (project.getUserData(SUPPRESS_WARNINGS_SESSION_KEY) == true) return
                showDisabledNotice(project)
            }
        }, 30_000L, 30_000L)
        project.putUserData(REMINDER_TIMER_KEY, timer)
    }

    private fun stopWarning(project: Project) {
        project.getUserData(REMINDER_TIMER_KEY)?.cancel()
        project.putUserData(REMINDER_TIMER_KEY, null)
    }

    fun showDisabledNotice(project: Project) {
        if (project.getUserData(SUPPRESS_WARNINGS_SESSION_KEY) == true) return

        val n = NotificationGroupManager.getInstance()
            .getNotificationGroup("DevRhythm")
            .createNotification(
                "DevRhythm is disabled",
                "Please work in a <b>detached</b> project window. Stats are not being tracked or written.",
                NotificationType.WARNING
            )

        n.addAction(
            NotificationAction.createSimpleExpiring("Don't show again (this session)") {
                project.putUserData(SUPPRESS_WARNINGS_SESSION_KEY, true)
                stopWarning(project)
            }
        )

        n.notify(project)
    }

    private fun projectDataDir(project: Project): File {
        val base = project.basePath ?: System.getProperty("user.home")
        val dir = File(base, ".devrhythm")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readStatusFromCsv(project: Project): String? {
        return try {
            val baseDir = projectDataDir(project)
            val user = System.getProperty("user.name")
            val file = File(baseDir, "${user}_${project.name}_status.csv")
            if (!file.exists()) return null
            BufferedReader(FileReader(file)).use { br ->
                br.readLine() ?: return null
                val row = br.readLine() ?: return null
                val parts = row.split(",")
                val status = parts.last().trim()
                if (status == "Show" || status == "Do not Show") status else null
            }
        } catch (_: Exception) { null }
    }
}