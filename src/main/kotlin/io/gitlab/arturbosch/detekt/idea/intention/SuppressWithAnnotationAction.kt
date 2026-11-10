package io.gitlab.arturbosch.detekt.idea.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.DetektBundle

class SuppressWithAnnotationAction(
    private val ruleId: String,
    private val offset: Int,
    private val targetName: String?,
) : IntentionAction, PriorityAction {

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun startInWriteAction(): Boolean = true

    override fun getText(): String =
        if (targetName == null) {
            DetektBundle.message("detekt.actions.suppressFile", ruleId)
        } else {
            DetektBundle.message("detekt.actions.suppressOn", ruleId, targetName)
        }

    override fun getFamilyName(): String = DETEKT

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = file.isWritable

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val document = editor.document
        val target = suppressTarget(file, offset)
        if (target == null) {
            document.insertString(0, "@file:Suppress(\"$ruleId\")\n\n")
        } else {
            addSuppression(document, target)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun addSuppression(document: Document, target: PsiElement) {
        val existing = target.childSequence()
            .filter { it.isOfType(MODIFIER_LIST) }
            .flatMap { it.childSequence() }
            .firstOrNull { it.isOfType(ANNOTATION_ENTRY) && it.text.startsWith("@Suppress(") }
        if (existing != null) {
            val text = existing.text
            val open = existing.textRange.startOffset + text.indexOf('(') + 1
            val close = existing.textRange.startOffset + text.lastIndexOf(')')
            val inner = document.charsSequence.subSequence(open, close).toString().trim()
            val replacement = if (inner.isEmpty() || inner == "\"\"") "\"$ruleId\"" else "$inner, \"$ruleId\""
            document.replaceString(open, close, replacement)
            return
        }
        val anchor = target.childSequence().firstOrNull { it !is PsiComment && it !is PsiWhiteSpace } ?: target
        val start = anchor.textRange.startOffset
        val lineStart = document.getLineStartOffset(document.getLineNumber(start))
        val prefix = document.charsSequence.subSequence(lineStart, start).toString()
        val separator = if (prefix.isBlank()) "\n$prefix" else " "
        document.insertString(start, "@Suppress(\"$ruleId\")$separator")
    }
}

internal fun suppressTarget(file: PsiFile, offset: Int): PsiElement? =
    generateSequence(file.findElementAt(offset)) { it.parent }
        .takeWhile { it !is PsiFile }
        .firstOrNull { element -> DECLARATION_TYPES.any { element.isOfType(it) } }

internal fun suppressTargetName(file: PsiFile, offset: Int): String? =
    suppressTarget(file, offset)?.let { (it as? PsiNamedElement)?.name ?: it.text.lineSequence().first().take(40) }

private fun PsiElement.childSequence(): Sequence<PsiElement> = generateSequence(firstChild) { it.nextSibling }

private fun PsiElement.isOfType(name: String): Boolean = node?.elementType?.toString() == name

private const val MODIFIER_LIST = "MODIFIER_LIST"
private const val ANNOTATION_ENTRY = "ANNOTATION_ENTRY"
private val DECLARATION_TYPES = listOf(
    "FUN",
    "PROPERTY",
    "CLASS",
    "OBJECT_DECLARATION",
    "SECONDARY_CONSTRUCTOR",
    "TYPEALIAS",
)
