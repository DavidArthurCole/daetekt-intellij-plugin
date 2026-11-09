package io.gitlab.arturbosch.detekt.idea.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.KOTLIN_FILE_EXTENSIONS
import io.gitlab.arturbosch.detekt.idea.intention.RuleRef
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

    internal fun runAction(project: Project, psiFile: PsiFile, only: RuleRef? = null) {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(psiFile) ?: return
        documents.commitDocument(document)
        val stamp = document.modificationStamp
        val title = DetektBundle.message("detekt.actions.autoCorrect")
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val service = ConfiguredService(project)
                val problems = service.validate()
                if (problems.isNotEmpty()) {
                    showNotification(problems, project)
                    return
                }
                val corrected = service.execute(psiFile, autoCorrect = true, only = only).correctedContent
                if (corrected == null) {
                    if (only != null) {
                        showNotification(title, DetektBundle.message("detekt.actions.autoCorrectNoChange", only.ruleId), project)
                    }
                    return
                }
                ApplicationManager.getApplication().invokeLater({
                    if (document.modificationStamp != stamp) return@invokeLater
                    WriteCommandAction.runWriteCommandAction(project, title, null, {
                        replaceChangedRange(document, corrected)
                        documents.commitDocument(document)
                    }, psiFile)
                }, project.disposed)
            }
        }.queue()
    }
}

internal fun replaceChangedRange(document: Document, corrected: String) {
    val current = document.charsSequence
    val limit = minOf(current.length, corrected.length)
    var prefix = 0
    while (prefix < limit && current[prefix] == corrected[prefix]) prefix++
    var suffix = 0
    while (suffix < limit - prefix && current[current.length - 1 - suffix] == corrected[corrected.length - 1 - suffix]) suffix++
    document.replaceString(prefix, current.length - suffix, corrected.subSequence(prefix, corrected.length - suffix))
}
