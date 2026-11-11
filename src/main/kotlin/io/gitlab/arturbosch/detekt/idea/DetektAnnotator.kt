package io.gitlab.arturbosch.detekt.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineSeverity
import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.intention.AddToBaselineAction
import io.gitlab.arturbosch.detekt.idea.intention.ApplyFixTemplateAction
import io.gitlab.arturbosch.detekt.idea.intention.AutoCorrectIntention
import io.gitlab.arturbosch.detekt.idea.intention.SuppressWithAnnotationAction
import io.gitlab.arturbosch.detekt.idea.intention.editWithin
import io.gitlab.arturbosch.detekt.idea.util.clearShownProblems
import io.gitlab.arturbosch.detekt.idea.util.isDetektEnabled
import io.gitlab.arturbosch.detekt.idea.util.showNotificationOnce

class DetektAnnotations(
    val findings: List<EngineFinding>,
    val templates: Map<String, List<FixTemplate>>,
    val retry: Boolean = false,
) {

    companion object {
        val EMPTY = DetektAnnotations(emptyList(), emptyMap())
    }
}

class DetektAnnotator : ExternalAnnotator<PsiFile, DetektAnnotations>() {

    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun doAnnotate(collectedInfo: PsiFile): DetektAnnotations {
        val project = collectedInfo.project
        if (!project.isDetektEnabled() || !isKotlinFile(collectedInfo)) {
            return DetektAnnotations.EMPTY
        }
        val document = collectedInfo.viewProvider.document
        val content = document?.text ?: collectedInfo.text
        val stamp = document?.modificationStamp ?: collectedInfo.modificationStamp
        return DetektResults.resultFor(collectedInfo, stamp) { analyze(project, collectedInfo, content) }
    }

    private fun analyze(project: Project, file: PsiFile, content: String): DetektAnnotations {
        val service = ConfiguredService(project)
        val problems = service.validate()
        if (problems.isNotEmpty()) {
            showNotificationOnce(problems, project)
            return DetektAnnotations.EMPTY
        }

        val outcome = service.execute(file, autoCorrect = false, providedContent = content)
        val configProblem = outcome.configProblem
        if (configProblem != null) {
            showNotificationOnce(listOf(configProblem), project)
            return DetektAnnotations.EMPTY
        }
        if (outcome.retryable) return DetektAnnotations(emptyList(), emptyMap(), retry = true)
        clearShownProblems(project)
        val templates = if (outcome.findings.isEmpty()) emptyMap() else service.fixTemplates()
        return DetektAnnotations(outcome.findings, templates)
    }

    private fun isKotlinFile(psiFile: PsiFile): Boolean =
        psiFile.language.id == KOTLIN_LANGUAGE_ID ||
            psiFile.getUserData(TEST_KOTLIN_LANGUAGE_ID_KEY) == KOTLIN_LANGUAGE_ID

    override fun apply(
        file: PsiFile,
        annotationResult: DetektAnnotations,
        holder: AnnotationHolder,
    ) {
        val settings = file.project.service<DetektPluginSettings>()
        val hasCustomConfig = settings.configurationFilePaths.isNotEmpty()
        val text = file.viewProvider.document?.charsSequence ?: file.text
        for (finding in annotationResult.findings) {
            val textRange = finding.toTextRangeWithin(file.textRange)
            val message = "detekt - ${finding.ruleId}: ${finding.message}"
            val severity = highlightSeverity(finding.severity, hasCustomConfig, settings.treatAsErrors)
            val annotationBuilder = holder.newAnnotation(severity, message).range(textRange)

            if (textRange == file.textRange) {
                annotationBuilder.fileLevel()
            }

            for (template in annotationResult.templates[finding.ruleId].orEmpty()) {
                val edit = template.editWithin(text, textRange.startOffset, textRange.endOffset) ?: continue
                annotationBuilder.withFix(ApplyFixTemplateAction(template, textRange, edit.original))
            }
            annotationBuilder.withFix(AutoCorrectIntention(finding))
            annotationBuilder.withFix(AutoCorrectIntention())
            annotationBuilder.withFix(SuppressWithAnnotationAction(finding.ruleId, textRange.startOffset, textRange == file.textRange))
            if (finding.ruleSetId != KTLINT_RULE_SET_ID) {
                annotationBuilder.withFix(AddToBaselineAction(finding))
            }

            annotationBuilder.create()
        }
    }
}

internal fun highlightSeverity(severity: EngineSeverity, hasCustomConfig: Boolean, treatAsError: Boolean): HighlightSeverity {
    if (treatAsError) {
        return HighlightSeverity.ERROR
    }
    if (!hasCustomConfig) {
        return HighlightSeverity.WARNING
    }
    return when (severity) {
        EngineSeverity.Error -> HighlightSeverity.ERROR
        EngineSeverity.Warning -> HighlightSeverity.WARNING
        EngineSeverity.Info -> HighlightSeverity.WEAK_WARNING
    }
}

internal fun EngineFinding.toTextRangeWithin(fileRange: TextRange): TextRange {
    val end = endOffset.coerceIn(fileRange.startOffset, fileRange.endOffset)
    val start = startOffset.coerceIn(fileRange.startOffset, end)
    if (start == end && start > fileRange.startOffset) {
        return TextRange.create(start - 1, end)
    }
    return TextRange.create(start, end)
}
