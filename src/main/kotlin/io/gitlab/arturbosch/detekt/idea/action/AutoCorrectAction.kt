package io.gitlab.arturbosch.detekt.idea.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.KOTLIN_FILE_EXTENSIONS
import io.gitlab.arturbosch.detekt.idea.util.PluginUtils
import io.gitlab.arturbosch.detekt.idea.util.isDetektEnabled
import io.gitlab.arturbosch.detekt.idea.util.showNotification

class AutoCorrectAction : AnAction(PluginUtils.pluginIcon()) {

    private val logger = logger<AutoCorrectAction>()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = event.getData(CommonDataKeys.PROJECT)
        event.presentation.isEnabledAndVisible = file != null &&
            project != null &&
            !file.fileSystem.isReadOnly &&
            file.extension in KOTLIN_FILE_EXTENSIONS &&
            project.isDetektEnabled()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val project = event.getData(CommonDataKeys.PROJECT) ?: return
        if (virtualFile.fileSystem.isReadOnly) {
            return
        }
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        runCatching { runAction(project, psiFile) }
            .onFailure { logger.error("Unexpected error while performing auto correct action", it) }
            .getOrThrow()
    }

    internal fun runAction(project: Project, psiFile: PsiFile) {
        val service = ConfiguredService(project)
        val problems = service.validate()
        if (problems.isNotEmpty()) {
            showNotification(problems, project)
            return
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        val stamp = document.modificationStamp
        val corrected = service.execute(psiFile, autoCorrect = true).correctedContent ?: return
        WriteCommandAction.runWriteCommandAction(project, DetektBundle.message("detekt.actions.autoCorrect"), null, {
            if (document.modificationStamp == stamp) {
                document.setText(corrected)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }, psiFile)
    }
}
