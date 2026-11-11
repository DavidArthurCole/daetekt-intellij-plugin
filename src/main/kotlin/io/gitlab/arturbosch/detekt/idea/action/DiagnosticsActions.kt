package io.gitlab.arturbosch.detekt.idea.action

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.NOTIFICATION_GROUP_ID
import io.gitlab.arturbosch.detekt.idea.config.DetektConfig
import io.gitlab.arturbosch.detekt.idea.util.DetektLog
import io.gitlab.arturbosch.detekt.idea.util.DetektMetrics
import io.gitlab.arturbosch.detekt.idea.util.clearShownProblems
import io.gitlab.arturbosch.detekt.idea.util.restartAnalysis

abstract class DetektProjectAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        perform(e.project ?: return)
    }

    protected abstract fun perform(project: Project)
}

class RestartAnalysisAction : DetektProjectAction() {

    override fun perform(project: Project) {
        clearShownProblems(project)
        restartAnalysis(project)
        DetektLog.info("analysis restarted from the menu")
    }
}

class ShowTimingsAction : DetektProjectAction() {

    override fun perform(project: Project) {
        val summary = DetektMetrics.summary()
        val content = if (summary == null) {
            DetektBundle.message("detekt.timings.none")
        } else {
            DetektBundle.message(
                "detekt.timings.summary",
                summary.window,
                summary.median,
                summary.p95,
                summary.slowest,
                summary.runs,
                summary.failures,
                DetektLog.file.toString(),
            )
        }
        Notification(NOTIFICATION_GROUP_ID, DetektBundle.message("detekt.timings.title"), content, NotificationType.INFORMATION)
            .notify(project)
    }
}

class OpenLogAction : DetektProjectAction() {

    override fun perform(project: Project) {
        DetektLog.info("log opened from the menu")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(DetektLog.file) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }
}

class OpenSettingsAction : DetektProjectAction() {

    override fun perform(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, DetektConfig::class.java)
    }
}
