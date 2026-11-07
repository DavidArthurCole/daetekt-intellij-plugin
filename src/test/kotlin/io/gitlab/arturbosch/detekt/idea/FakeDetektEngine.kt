package io.gitlab.arturbosch.detekt.idea

import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisOutcome
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisRequest
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineSeverity
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import java.nio.file.Path

class FakeDetektEngine(private val outcome: AnalysisOutcome = AnalysisOutcome.EMPTY) : DetektEngine {

    val requests = mutableListOf<AnalysisRequest>()

    override fun analyze(request: AnalysisRequest): AnalysisOutcome {
        requests += request
        return outcome
    }

    override fun suppressInBaseline(baseline: Path, baselineId: String) {
        error("not used")
    }

    override fun describeRules(pluginPaths: List<Path>): List<RuleDescription> = error("not used")

    override fun loadFixTemplates(path: Path): Map<String, List<FixTemplate>> = error("not used")
}

fun finding(startOffset: Int, endOffset: Int): EngineFinding = EngineFinding(
    ruleId = "EmptyClassBlock",
    ruleSetId = "empty-blocks",
    message = "The class or object Poko is empty.",
    description = "Empty block of code detected.",
    severity = EngineSeverity.Warning,
    startOffset = startOffset,
    endOffset = endOffset,
    line = 1,
    column = 1,
    baselineId = "EmptyClassBlock:Poko.kt:Poko",
)
