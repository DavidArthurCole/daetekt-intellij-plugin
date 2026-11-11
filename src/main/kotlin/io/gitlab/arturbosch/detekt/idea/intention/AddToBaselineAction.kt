package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.DetektResults
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.util.absoluteBaselinePath
import io.gitlab.arturbosch.detekt.idea.util.restartHighlighting
import kotlin.io.path.exists

class AddToBaselineAction(private val finding: EngineFinding) : IntentionAction, PriorityAction {

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.BOTTOM

    override fun startInWriteAction(): Boolean = false

    override fun getText(): String = DetektBundle.message("detekt.actions.addToBaseline", finding.ruleId)

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val baseline = absoluteBaselinePath(project, project.service<DetektPluginSettings>()) ?: return IntentionPreviewInfo.EMPTY
        return IntentionPreviewInfo.Html(
            HtmlChunk.text(DetektBundle.message("detekt.actions.baselinePreview", finding.baselineId, baseline.fileName.toString()))
        )
    }

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val baseline = absoluteBaselinePath(project, project.service<DetektPluginSettings>())
        return baseline != null && baseline.exists()
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            ConfiguredService(project).suppressInBaseline(finding.baselineId)
            DetektResults.invalidate()
            application.invokeLater({ restartHighlighting(file, "detekt baseline changed") }, project.disposed)
        }
    }
}
