package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle

class SuppressWithAnnotationAction(
    private val ruleId: String,
    private val offset: Int,
    private val fileLevel: Boolean,
) : IntentionAction, PriorityAction {

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun startInWriteAction(): Boolean = true

    override fun getText(): String =
        DetektBundle.message(if (fileLevel) "detekt.actions.suppressFile" else "detekt.actions.suppressLine", ruleId)

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        file.isWritable && offset <= editor.document.textLength

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val document = editor.document
        if (fileLevel) {
            document.insertString(0, "@file:Suppress(\"$ruleId\")\n")
        } else {
            suppressAbove(document, offset, ruleId)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

internal fun suppressAbove(document: Document, offset: Int, ruleId: String) {
    val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
    var above = line - 1
    while (above >= 0 && document.lineText(above).trimStart().startsWith("@")) {
        val existing = SUPPRESS.find(document.lineText(above))
        if (existing != null) {
            val group = existing.groups[1] ?: return
            val start = document.getLineStartOffset(above) + group.range.first
            val inner = group.value.trim()
            val quoted = "\"$ruleId\""
            when {
                inner.isEmpty() || inner == "\"\"" -> document.replaceString(start, start + group.value.length, quoted)
                quoted !in inner -> document.replaceString(start, start + group.value.length, "$inner, $quoted")
            }
            return
        }
        above--
    }
    val indent = document.lineText(line).takeWhile { it == ' ' || it == '\t' }
    document.insertString(document.getLineStartOffset(line), "$indent@Suppress(\"$ruleId\")\n")
}

private fun Document.lineText(line: Int): String =
    getText(TextRange(getLineStartOffset(line), getLineEndOffset(line)))

private val SUPPRESS = Regex("""@Suppress\(([^)]*)\)""")
