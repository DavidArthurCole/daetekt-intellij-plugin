package io.gitlab.arturbosch.detekt.idea.engine.api

import java.nio.file.Path
import java.util.concurrent.ExecutorService

const val KTLINT_RULE_SET_ID = "ktlint"

interface DetektEngine {

    fun analyze(request: AnalysisRequest): AnalysisOutcome

    fun suppressInBaseline(baseline: Path, baselineId: String)

    fun describeRules(request: RulesRequest): List<RuleDescription>

    fun loadFixTemplates(path: Path): Map<String, List<FixTemplate>>
}

class FixTemplate(
    val find: String,
    val replace: String,
    val ignoreCase: Boolean,
)

class RulesRequest(
    val pluginPaths: List<Path>,
    val configPaths: List<Path>,
    val buildUponDefaultConfig: Boolean,
    val activateAllRules: Boolean,
    val enableFormatting: Boolean,
)

class RuleDescription(
    val ruleSetId: String,
    val ruleId: String,
    val description: String,
    val url: String?,
    val requiresTypeResolution: Boolean,
    val source: String?,
    val className: String? = null,
    val custom: Boolean = false,
    val active: Boolean = false,
    val autoCorrect: Boolean = false,
    val options: List<RuleOption> = emptyList(),
    val rationale: String? = null,
    val examples: List<RuleExample> = emptyList(),
)

class RuleExample(
    val title: String?,
    val before: String?,
    val after: String?,
    val note: String?,
)

class RuleOption(
    val name: String,
    val description: String?,
    val defaultValue: String?,
    val configuredValue: String?,
)

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
    val classpath: List<Path> = emptyList(),
    val jdkHome: Path? = null,
    val onlyRuleSetId: String? = null,
    val onlyRuleId: String? = null,
)

class AnalysisOutcome(
    val findings: List<EngineFinding>,
    val correctedContent: String?,
    val configProblem: String?,
    val failure: String? = null,
    val retryable: Boolean = false,
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
