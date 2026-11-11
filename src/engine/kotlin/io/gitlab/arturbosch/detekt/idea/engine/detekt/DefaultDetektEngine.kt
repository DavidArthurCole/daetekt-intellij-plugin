package io.gitlab.arturbosch.detekt.idea.engine.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Issue
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import dev.detekt.api.Severity
import dev.detekt.api.suppressed
import dev.detekt.tooling.api.AnalysisMode
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
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleExample
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleOption
import io.gitlab.arturbosch.detekt.idea.engine.api.RulesRequest
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.jar.JarFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

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
                is InvalidConfig -> return AnalysisOutcome(emptyList(), null, error.message ?: "Invalid detekt configuration.", null, false)
                is UnexpectedError -> throw error.cause
                else -> throw error
            }
            val findings = result.container?.issues.orEmpty()
                .filterNot { it.suppressed }
                .map { it.toFinding() }
            val corrected = if (request.autoCorrect) mirror.readText().takeIf { it != request.content } else null
            return AnalysisOutcome(findings, corrected, null, null, false)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    override fun describeRules(request: RulesRequest): List<RuleDescription> {
        val urls = request.pluginPaths.filter { Files.isRegularFile(it) }.map { it.toUri().toURL() }.toTypedArray()
        return URLClassLoader(urls, DefaultDetektEngine::class.java.classLoader).use { loader ->
            withEngineContext {
                val userLayers = request.configPaths
                    .filter { Files.isRegularFile(it) }
                    .mapNotNull { yaml(it.readText()) }
                    .asReversed()
                val bundled = listOfNotNull(bundledDefaults())
                ServiceLoader.load(RuleSetProvider::class.java, loader)
                    .flatMap { provider -> provider.describe(provider.javaClass.classLoader === loader, userLayers, bundled, request) }
                    .sortedWith(compareBy({ it.ruleSetId }, { it.ruleId }))
            }
        }
    }

    private fun RuleSetProvider.describe(
        custom: Boolean,
        userLayers: List<Map<*, *>>,
        bundled: List<Map<*, *>>,
        request: RulesRequest,
    ): List<RuleDescription> {
        val location = runCatching { Path.of(javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        val ruleSet = runCatching { instance() }.getOrElse { return emptyList() }
        val ruleSetId = ruleSet.id.value
        val defaults = listOfNotNull(packagedYaml(location, PACKAGED_CONFIG)) + bundled
        val effective = if (request.buildUponDefaultConfig || userLayers.isEmpty()) userLayers + defaults else userLayers
        val ruleSetActive = effective.lookup(ruleSetId, Config.ACTIVE_KEY) != false &&
            (request.enableFormatting || ruleSetId != KTLINT_RULE_SET_ID)
        val docs = packagedYaml(location, PACKAGED_DOCS).orEmpty()
        return ruleSet.rules.map { (name, factory) ->
            val ruleId = name.value
            val rule = runCatching { factory(Config.empty) }.getOrNull()
            val doc = (docs["$ruleSetId/$ruleId"] ?: docs[ruleId]) as? Map<*, *>
            RuleDescription(
                ruleSetId = ruleSetId,
                ruleId = ruleId,
                description = rule?.description.orEmpty(),
                url = rule?.url?.toString(),
                requiresTypeResolution = rule is RequiresAnalysisApi,
                source = location?.name,
                className = rule?.javaClass?.name,
                custom = custom,
                active = ruleSetActive &&
                    (request.activateAllRules || effective.lookup(ruleSetId, ruleId, Config.ACTIVE_KEY) == true),
                autoCorrect = defaults.lookup(ruleSetId, ruleId, Config.AUTO_CORRECT_KEY) != null,
                options = rule?.let { options(it.javaClass, ruleSetId, ruleId, defaults, userLayers) }.orEmpty(),
                rationale = doc?.get("rationale")?.toString()?.trim()?.ifEmpty { null },
                examples = (doc?.get("examples") as? List<*>).orEmpty().mapNotNull { it.toExample() },
            )
        }
    }

    private fun Any?.toExample(): RuleExample? {
        val fields = this as? Map<*, *> ?: return null
        val before = fields["before"]?.toString()?.trimEnd()?.ifEmpty { null }
        val after = fields["after"]?.toString()?.trimEnd()?.ifEmpty { null }
        if (before == null && after == null) return null
        return RuleExample(fields["title"]?.toString(), before, after, fields["note"]?.toString())
    }

    private fun options(
        type: Class<*>,
        ruleSetId: String,
        ruleId: String,
        defaults: List<Map<*, *>>,
        userLayers: List<Map<*, *>>,
    ): List<RuleOption> {
        val described = configurationDescriptions(type)
        val configured = (defaults.lookup(ruleSetId, ruleId) as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()
        return (configured + described.keys)
            .distinct()
            .filterNot { it == Config.ACTIVE_KEY }
            .map { key ->
                RuleOption(
                    name = key,
                    description = described[key],
                    defaultValue = defaults.lookup(ruleSetId, ruleId, key)?.let(::render),
                    configuredValue = userLayers.lookup(ruleSetId, ruleId, key)?.let(::render),
                )
            }
    }

    private fun configurationDescriptions(type: Class<*>): Map<String, String> =
        generateSequence(type) { it.superclass }
            .takeWhile { it != Rule::class.java && it != Any::class.java }
            .flatMap { runCatching { it.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
            .mapNotNull { method ->
                val description = method.getAnnotation(Configuration::class.java)?.description
                val property = propertyName(method.name)
                if (description == null || property == null) null else property to description
            }
            .toMap()

    private fun propertyName(method: String): String? {
        if (!method.endsWith(ANNOTATIONS_SUFFIX)) return null
        val base = method.removeSuffix(ANNOTATIONS_SUFFIX)
        if (!base.startsWith("get") || base.length == 3) return base
        return base.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
    }

    private fun render(value: Any?): String = when (value) {
        is String -> "'$value'"
        is Collection<*> -> value.joinToString(", ", "[", "]") { render(it) }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}: ${render(it.value)}" }
        else -> value.toString()
    }

    private fun List<Map<*, *>>.lookup(vararg path: String): Any? = firstNotNullOfOrNull { layer ->
        path.fold<String, Any?>(layer) { node, key -> (node as? Map<*, *>)?.get(key) }
    }

    private fun bundledDefaults(): Map<*, *>? =
        DefaultDetektEngine::class.java.classLoader.getResourceAsStream(DEFAULT_CONFIG)
            ?.bufferedReader()
            ?.use { yaml(it.readText()) }

    private fun packagedYaml(location: Path?, entryName: String): Map<*, *>? {
        if (location == null) return null
        if (Files.isDirectory(location)) {
            return location.resolve(entryName).takeIf { Files.isRegularFile(it) }?.let { yaml(it.readText()) }
        }
        if (!Files.isRegularFile(location)) return null
        return runCatching {
            JarFile(location.toFile()).use { jar ->
                jar.getJarEntry(entryName)?.let { entry -> jar.getInputStream(entry).bufferedReader().use { yaml(it.readText()) } }
            }
        }.getOrNull()
    }

    private fun yaml(text: String): Map<*, *>? =
        runCatching { Load(LoadSettings.builder().build()).loadFromString(text) as? Map<*, *> }.getOrNull()

    override fun loadFixTemplates(path: Path): Map<String, List<FixTemplate>> {
        val root = Load(LoadSettings.builder().build()).loadFromString(path.readText()) as? Map<*, *> ?: return emptyMap()
        return root.entries.associate { (ruleId, templates) ->
            ruleId.toString() to (templates as? List<*>).orEmpty().mapNotNull { it.toFixTemplate() }
        }
    }

    private fun Any?.toFixTemplate(): FixTemplate? {
        val fields = this as? Map<*, *> ?: return null
        val find = fields["find"]?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        return FixTemplate(find, fields["replace"]?.toString().orEmpty(), fields["ignoreCase"] == true)
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
            analysisMode = if (request.classpath.isEmpty()) AnalysisMode.light else AnalysisMode.full
        }
        compiler {
            classpath = request.classpath
            jdkHome = request.jdkHome
        }
        rules {
            autoCorrect = request.autoCorrect
            activateAllRules = request.activateAllRules
            failurePolicy = RulesSpec.FailurePolicy.NeverFail
            val ruleSetId = request.onlyRuleSetId
            val ruleId = request.onlyRuleId
            if (ruleSetId != null && ruleId != null) {
                runPolicy = RulesSpec.RunPolicy.RestrictToSingleRule(RuleSetId(ruleSetId), ruleId)
            }
        }
        config {
            useDefaultConfig = request.buildUponDefaultConfig
            configPaths = if (request.enableFormatting) request.configPaths else request.configPaths + listOf(formattingOff(workspace))
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

private const val DEFAULT_CONFIG = "default-detekt-config.yml"
private const val PACKAGED_CONFIG = "config/config.yml"
private const val PACKAGED_DOCS = "META-INF/daetekt/rules.yml"
private const val ANNOTATIONS_SUFFIX = "\$annotations"

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
