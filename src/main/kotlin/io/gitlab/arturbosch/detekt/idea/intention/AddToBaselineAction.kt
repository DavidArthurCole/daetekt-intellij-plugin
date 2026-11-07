package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.util.absoluteBaselinePath
import kotlin.io.path.exists

class AddToBaselineAction(private val finding: EngineFinding) : IntentionAction, LowPriorityAction {

    override fun startInWriteAction(): Boolean = false

    override fun getText(): String = DetektBundle.message("detekt.actions.addToBaseline", finding.ruleId)

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val baseline = absoluteBaselinePath(project, project.service<DetektPluginSettings>())
        return baseline != null && baseline.exists()
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        ConfiguredService(project).suppressInBaseline(finding.baselineId)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}
