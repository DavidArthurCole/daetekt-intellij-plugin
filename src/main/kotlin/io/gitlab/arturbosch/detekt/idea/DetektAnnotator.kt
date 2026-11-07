package io.gitlab.arturbosch.detekt.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineSeverity
import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import io.gitlab.arturbosch.detekt.idea.intention.AddToBaselineAction
import io.gitlab.arturbosch.detekt.idea.intention.AutoCorrectIntention
import io.gitlab.arturbosch.detekt.idea.util.clearShownProblems
import io.gitlab.arturbosch.detekt.idea.util.isDetektEnabled
import io.gitlab.arturbosch.detekt.idea.util.showNotificationOnce

class DetektAnnotator : ExternalAnnotator<PsiFile, List<EngineFinding>>() {

    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun doAnnotate(collectedInfo: PsiFile): List<EngineFinding> {
        val project = collectedInfo.project
        if (!project.isDetektEnabled() || !isKotlinFile(collectedInfo)) {
            return emptyList()
        }

        val service = ConfiguredService(project)
        val problems = service.validate()
        if (problems.isNotEmpty()) {
            showNotificationOnce(problems, project)
            return emptyList()
        }

        val outcome = service.execute(collectedInfo, autoCorrect = false)
        val configProblem = outcome.configProblem
        if (configProblem != null) {
            showNotificationOnce(listOf(configProblem), project)
            return emptyList()
        }
        clearShownProblems(project)
        return outcome.findings
    }

    private fun isKotlinFile(psiFile: PsiFile): Boolean =
        psiFile.language.id == KOTLIN_LANGUAGE_ID ||
            psiFile.getUserData(TEST_KOTLIN_LANGUAGE_ID_KEY) == KOTLIN_LANGUAGE_ID

    override fun apply(
        file: PsiFile,
        annotationResult: List<EngineFinding>,
        holder: AnnotationHolder,
    ) {
        val settings = file.project.service<DetektPluginSettings>()
        val hasCustomConfig = settings.configurationFilePaths.isNotEmpty()
        for (finding in annotationResult) {
            val textRange = finding.toTextRangeWithin(file.textRange)
            val message = "detekt - ${finding.ruleId}: ${finding.message}"
            val severity = highlightSeverity(finding.severity, hasCustomConfig, settings.treatAsErrors)
            val annotationBuilder = holder.newAnnotation(severity, message).range(textRange)

            if (textRange == file.textRange) {
                annotationBuilder.fileLevel()
            }

            if (finding.ruleSetId == KTLINT_RULE_SET_ID) {
                annotationBuilder.withFix(AutoCorrectIntention())
            } else {
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
