package com.devrhythm

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class DevRhythmStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = DevRhythmStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "DevRhythm Status"

    /**
     * Widget visibility control - public API (not internal).
     * Returns false when widget should be hidden, true when it should be shown.
     * The IDE automatically handles widget lifecycle based on this return value.
     */
    override fun isAvailable(project: Project): Boolean {
        if (project.getUserData(IdleToolWindowFactory.DISABLED_KEY) == true) return false
        val cached = project.getUserData(IdleToolWindowFactory.STATUS_KEY)
        if (cached != null) return cached == "Show"
        return true
    }

    override fun createWidget(project: Project): StatusBarWidget =
        DevRhythmStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        (widget as? DevRhythmStatusBarWidget)?.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    // ---------- CSV fallback (now reads from <projectRoot>/.DevRhythm) ----------

    /** Return <projectRoot>/.DevRhythm (create it if missing). */
    private fun projectDataDir(project: Project): File {
        val base = project.basePath ?: System.getProperty("user.home")
        val dir = File(base, ".devrhythm")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Suppress("unused")
    private fun readStatusFromCsv(project: Project): String? {
        return try {
            val baseDir = projectDataDir(project)
            val user = System.getProperty("user.name")
            val file = File(baseDir, "${user}_${project.name}_status.csv")
            if (!file.exists()) return null
            BufferedReader(FileReader(file)).use { br ->
                br.readLine() ?: return null // header
                val row = br.readLine() ?: return null
                val status = row.split(",").last().trim()
                if (status == "Show" || status == "Do not Show") status else null
            }
        } catch (_: Exception) { null }
    }
}