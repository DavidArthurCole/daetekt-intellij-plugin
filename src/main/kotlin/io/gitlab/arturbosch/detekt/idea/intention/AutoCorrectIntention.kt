package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.action.AutoCorrectAction
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding

class AutoCorrectIntention(private val finding: EngineFinding? = null) : IntentionAction, PriorityAction {

    override fun getPriority(): PriorityAction.Priority =
        if (finding == null) PriorityAction.Priority.LOW else PriorityAction.Priority.NORMAL

    override fun startInWriteAction(): Boolean = false

    override fun getText(): String =
        if (finding == null) {
            DetektBundle.message("detekt.actions.autoCorrectFile")
        } else {
            DetektBundle.message("detekt.actions.autoCorrectRule", finding.ruleId, summary(finding.message))
        }

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = file.isWritable

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        AutoCorrectAction().runAction(project, file, finding?.let { RuleRef(it.ruleSetId, it.ruleId, it.line) })
    }

    private fun summary(message: String): String {
        val line = message.lineSequence().first().trim()
        return if (line.length <= SUMMARY_LIMIT) line else line.take(SUMMARY_LIMIT - 1).trimEnd() + "…"
    }

    private companion object {
        const val SUMMARY_LIMIT = 70
    }
}

class RuleRef(val ruleSetId: String, val ruleId: String, val line: Int? = null)
