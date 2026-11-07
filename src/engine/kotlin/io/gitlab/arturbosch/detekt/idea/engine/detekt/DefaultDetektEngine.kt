package io.gitlab.arturbosch.detekt.idea.engine.detekt

import dev.detekt.api.Issue
import dev.detekt.api.Severity
import dev.detekt.api.suppressed
import dev.detekt.tooling.api.BaselineProvider
import dev.detekt.tooling.api.DetektProvider
import dev.detekt.tooling.api.InvalidConfig
import dev.detekt.tooling.api.UnexpectedError
import dev.detekt.tooling.api.spec.ProcessingSpec
import dev.detekt.tooling.api.spec.RulesSpec
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisOutcome
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisRequest
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineSeverity
import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DefaultDetektEngine : DetektEngine {

    private val provider by lazy { withEngineContext { DetektProvider.load() } }

    override fun analyze(request: AnalysisRequest): AnalysisOutcome {
        val workspace = Files.createTempDirectory("daetekt-")
        try {
            val mirror = workspace.resolve(request.relativePath.toString())
            mirror.createParentDirectories()
            mirror.writeText(request.content)
            val result = withEngineContext { provider.get(spec(request, workspace, mirror)).run() }
            when (val error = result.error) {
                null -> Unit
                is InvalidConfig -> return AnalysisOutcome(emptyList(), null, error.message ?: "Invalid detekt configuration.")
                is UnexpectedError -> throw error.cause
                else -> throw error
            }
            val findings = result.container?.issues.orEmpty()
                .filterNot { it.suppressed }
                .map { it.toFinding() }
            val corrected = if (request.autoCorrect) mirror.readText().takeIf { it != request.content } else null
            return AnalysisOutcome(findings, corrected, null)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    override fun suppressInBaseline(baseline: Path, baselineId: String) {
        withEngineContext {
            val baselines = BaselineProvider.load()
            val current = baselines.read(baseline)
            baselines.write(baseline, baselines.of(current.manuallySuppressedIssues + baselineId, current.currentIssues))
        }
    }

    private fun spec(request: AnalysisRequest, workspace: Path, mirror: Path) = ProcessingSpec {
        project {
            basePath = workspace
            inputPaths = listOf(mirror)
        }
        rules {
            autoCorrect = request.autoCorrect
            activateAllRules = request.activateAllRules
            failurePolicy = RulesSpec.FailurePolicy.NeverFail
        }
        config {
            useDefaultConfig = request.buildUponDefaultConfig
            configPaths = if (request.enableFormatting) request.configPaths else request.configPaths + formattingOff(workspace)
        }
        baseline {
            path = request.baseline
        }
        extensions {
            fromPaths { request.pluginPaths }
        }
        execution {
            executorService = request.executor
        }
        logging {
            debug = request.debug
            outputChannel = request.outputChannel ?: Silent
            errorChannel = request.errorChannel ?: Silent
        }
    }

    private fun formattingOff(workspace: Path): Path =
        workspace.resolve("daetekt-formatting-off.yml").apply { writeText("$KTLINT_RULE_SET_ID:\n  active: false\n") }

    private fun <T> withEngineContext(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = DefaultDetektEngine::class.java.classLoader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}

private object Silent : Appendable {

    override fun append(csq: CharSequence?): Appendable = this

    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = this

    override fun append(c: Char): Appendable = this
}

internal fun Issue.toFinding(): EngineFinding = EngineFinding(
    ruleId = ruleInstance.id,
    ruleSetId = ruleInstance.ruleSetId.value,
    message = message,
    description = ruleInstance.description,
    severity = when (severity) {
        Severity.Error -> EngineSeverity.Error
        Severity.Warning -> EngineSeverity.Warning
        Severity.Info -> EngineSeverity.Info
    },
    startOffset = location.text.start,
    endOffset = location.text.end,
    line = location.source.line,
    column = location.source.column,
    baselineId = "${ruleInstance.id}:${location.path.name}:${entity.signature}",
)
