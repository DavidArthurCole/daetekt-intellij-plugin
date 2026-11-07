package io.gitlab.arturbosch.detekt.idea.engine

import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisRequest
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DetektEngineIntegrationTest {

    private val engine: DetektEngine = EngineLoader.load(
        Path(System.getProperty(DETEKT_RUNTIME_PROPERTY)),
        DetektEngineIntegrationTest::class.java.classLoader,
    )

    private val rulesJar = Path(System.getProperty("daetekt.test.rulesJar"))

    @TempDir
    lateinit var dir: Path

    @Test
    fun detektRunsInItsOwnClassLoader() {
        assertThat(engine.javaClass.classLoader).isInstanceOf(EngineClassLoader::class.java)
        assertThatThrownBy { Class.forName("dev.detekt.api.Rule", false, javaClass.classLoader) }
            .isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun customRuleFromPluginJarReports() {
        val outcome = engine.analyze(request(config = activateTestRules()))

        assertThat(outcome.configProblem).isNull()
        assertThat(outcome.findings.map { it.ruleId }).contains("FileMarker")
    }

    @Test
    fun analysisApiRuleIsSkippedWithoutError() {
        val outcome = engine.analyze(request(config = activateTestRules()))

        assertThat(outcome.configProblem).isNull()
        assertThat(outcome.findings.map { it.ruleId }).contains("FileMarker").doesNotContain("TypeResolvedFileMarker")
    }

    @Test
    fun builtInAnalysisApiRuleIsSkippedWithoutError() {
        val config = config(
            """
            potential-bugs:
              IgnoredReturnValue:
                active: true
                restrictToConfig: false
            """
        )
        val content = "package demo\n\nfun f(list: List<Int>) {\n    list.map { it + 1 }\n}\n"

        val outcome = engine.analyze(request(content = content, config = config))

        assertThat(outcome.configProblem).isNull()
        assertThat(outcome.findings.map { it.ruleId }).doesNotContain("IgnoredReturnValue")
    }

    @Test
    fun manuallySuppressedBaselineEntryHidesFinding() {
        val config = activateTestRules()
        val baseline = dir.resolve("baseline.xml")
        baseline.writeText("<?xml version=\"1.0\" ?>\n<SmellBaseline>\n<ManuallySuppressedIssues/>\n<CurrentIssues/>\n</SmellBaseline>\n")
        val marker = engine.analyze(request(config = config)).findings.single { it.ruleId == "FileMarker" }

        engine.suppressInBaseline(baseline, marker.baselineId)
        val outcome = engine.analyze(request(config = config, baseline = baseline))

        assertThat(baseline.readText()).contains(marker.baselineId)
        assertThat(outcome.findings.map { it.ruleId }).doesNotContain("FileMarker")
    }

    @Test
    fun unknownPropertyOnBuiltInRuleIsReportedAsConfigProblem() {
        val config = config(
            """
            config:
              validation: true
            style:
              MagicNumber:
                notARealProperty: 1
            """
        )

        val outcome = engine.analyze(request(config = config))

        assertThat(outcome.configProblem).isNotNull()
        assertThat(outcome.findings).isEmpty()
    }

    @Test
    fun pluginRuleSetPropertiesAreNotValidated() {
        val config = config(
            """
            config:
              validation: true
            daetekt-test:
              FileMarker:
                active: true
                propertyOnlyThePluginKnows: 1
            """
        )

        val outcome = engine.analyze(request(config = config))

        assertThat(outcome.configProblem).isNull()
        assertThat(outcome.findings.map { it.ruleId }).contains("FileMarker")
    }

    @Test
    fun configExcludesMatchProjectRelativePath() {
        val config = config(
            """
            daetekt-test:
              FileMarker:
                active: true
                excludes: ['**/test/**']
            """
        )

        val excluded = engine.analyze(request(config = config, relativePath = Path("src/test/kotlin/Demo.kt")))
        val included = engine.analyze(request(config = config, relativePath = Path("src/main/kotlin/Demo.kt")))

        assertThat(excluded.findings.map { it.ruleId }).doesNotContain("FileMarker")
        assertThat(included.findings.map { it.ruleId }).contains("FileMarker")
    }

    @Test
    fun ktlintRulesReportOnlyWhenFormattingIsEnabled() {
        val content = "package demo\n\nval a = 1\n\n\n\nval b = 2\n"

        val enabled = engine.analyze(request(content = content, enableFormatting = true))
        val disabled = engine.analyze(request(content = content, enableFormatting = false))

        assertThat(enabled.findings.filter { it.ruleSetId == KTLINT_RULE_SET_ID }.map { it.ruleId })
            .contains("NoConsecutiveBlankLines")
        assertThat(disabled.findings.map { it.ruleSetId }).doesNotContain(KTLINT_RULE_SET_ID)
    }

    @Test
    fun autoCorrectReturnsFixedContentWithoutTouchingTheRealFile() {
        val content = "package demo\n\nval a = 1\n\n\n\nval b = 2\n"

        val outcome = engine.analyze(request(content = content, enableFormatting = true, autoCorrect = true))

        assertThat(outcome.correctedContent).isNotNull().doesNotContain("\n\n\n").contains("val b = 2")
    }

    private fun activateTestRules(): Path = config(
        """
        daetekt-test:
          FileMarker:
            active: true
          TypeResolvedFileMarker:
            active: true
        """
    )

    private fun config(yaml: String): Path = dir.resolve("detekt-${yaml.hashCode()}.yml").apply {
        writeText(yaml.trimIndent() + "\n")
    }

    private fun request(
        content: String = "package demo\n\nclass Demo\n",
        relativePath: Path = Path("src/main/kotlin/Demo.kt"),
        config: Path? = null,
        baseline: Path? = null,
        enableFormatting: Boolean = false,
        autoCorrect: Boolean = false,
    ) = AnalysisRequest(
        content = content,
        relativePath = relativePath,
        configPaths = listOfNotNull(config),
        baseline = baseline,
        pluginPaths = listOf(rulesJar),
        buildUponDefaultConfig = true,
        activateAllRules = false,
        enableFormatting = enableFormatting,
        autoCorrect = autoCorrect,
        debug = false,
        outputChannel = null,
        errorChannel = null,
        executor = null,
    )
}
