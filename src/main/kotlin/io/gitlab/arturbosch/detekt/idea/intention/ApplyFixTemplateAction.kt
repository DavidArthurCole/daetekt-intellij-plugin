package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.util.restartHighlighting

class ApplyFixTemplateAction(
    private val template: FixTemplate,
    private val range: TextRange,
    private val original: String,
) : IntentionAction, PriorityAction {

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    override fun startInWriteAction(): Boolean = true

    override fun getText(): String =
        DetektBundle.message("detekt.actions.applyFixTemplate", original, matchCase(original, template.replace))

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        file.isWritable && template.editWithin(editor.document.charsSequence, range.startOffset, range.endOffset) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val document = editor.document
        val edit = template.editWithin(document.charsSequence, range.startOffset, range.endOffset) ?: return
        document.replaceString(edit.start, edit.end, edit.replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        restartHighlighting(file, "detekt fix applied")
    }
}
