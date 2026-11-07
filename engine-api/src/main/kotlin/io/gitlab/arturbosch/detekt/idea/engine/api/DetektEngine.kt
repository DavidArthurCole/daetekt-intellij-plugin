package io.gitlab.arturbosch.detekt.idea.engine.api

import java.nio.file.Path
import java.util.concurrent.ExecutorService

const val KTLINT_RULE_SET_ID = "ktlint"

interface DetektEngine {

    fun analyze(request: AnalysisRequest): AnalysisOutcome

    fun suppressInBaseline(baseline: Path, baselineId: String)
}

class AnalysisRequest(
    val content: String,
    val relativePath: Path,
    val configPaths: List<Path>,
    val baseline: Path?,
    val pluginPaths: List<Path>,
    val buildUponDefaultConfig: Boolean,
    val activateAllRules: Boolean,
    val enableFormatting: Boolean,
    val autoCorrect: Boolean,
    val debug: Boolean,
    val outputChannel: Appendable?,
    val errorChannel: Appendable?,
    val executor: ExecutorService?,
)

class AnalysisOutcome(
    val findings: List<EngineFinding>,
    val correctedContent: String?,
    val configProblem: String?,
) {

    companion object {
        val EMPTY = AnalysisOutcome(emptyList(), null, null)
    }
}

class EngineFinding(
    val ruleId: String,
    val ruleSetId: String,
    val message: String,
    val description: String,
    val severity: EngineSeverity,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val column: Int,
    val baselineId: String,
)

enum class EngineSeverity {
    Error,
    Warning,
    Info,
}
