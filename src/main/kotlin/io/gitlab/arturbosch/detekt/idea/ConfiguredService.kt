package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiFile
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.EngineLoader
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisOutcome
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisRequest
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import io.gitlab.arturbosch.detekt.idea.util.DirectExecutor
import io.gitlab.arturbosch.detekt.idea.util.SimpleAppendable
import io.gitlab.arturbosch.detekt.idea.util.absoluteBaselinePath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.exists

internal fun jarsIn(directory: Path?): List<Path> {
    if (directory == null || !Files.isDirectory(directory)) {
        return emptyList()
    }
    return Files.list(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
            .sorted()
            .toList()
    }
}

internal fun analysisRelativePath(projectDir: Path?, input: Path): Path {
    if (projectDir != null && projectDir.root == input.root && input.startsWith(projectDir)) {
        return projectDir.relativize(input)
    }
    return input.fileName
}

class ConfiguredService(
    private val project: Project,
    engineProvider: () -> DetektEngine = { EngineLoader.engine },
) {

    constructor(project: Project, engine: DetektEngine) : this(project, { engine })

    private val engine by lazy(engineProvider)
    private val logger = logger<ConfiguredService>()
    private val settings = project.service<DetektPluginSettings>()

    private val projectBasePath = project.basePath?.let(::Path)

    fun validate(): List<String> {
        val messages = mutableListOf<String>()

        fun checkPaths(paths: List<Path>, messagePrefix: String) {
            paths.filter { Files.notExists(it) }.forEach { messages += "$messagePrefix <b>$it</b> does not exist." }
            paths.filter { Files.isDirectory(it) }.forEach { messages += "$messagePrefix <b>$it</b> is a directory." }
        }

        checkPaths(explicitPluginPaths(), "Plugin jar")
        checkPaths(configPaths(), "Configuration file")

        val directory = pluginJarsDirectory()
        if (directory != null && !Files.isDirectory(directory)) {
            messages += "Plugin jars directory <b>$directory</b> does not exist."
        }

        val baseline = baseline()
        if (baseline != null && !baseline.exists()) {
            messages += "The provided baseline file <b>$baseline</b> does not exist."
        }

        return messages
    }

    private fun request(content: String, filename: String, autoCorrect: Boolean): AnalysisRequest {
        val projectDir = project.guessProjectDir()?.canonicalPath?.let { Paths.get(it) }
        val redirect = settings.redirectChannels
        return AnalysisRequest(
            content = content,
            relativePath = analysisRelativePath(projectDir, Paths.get(filename)),
            configPaths = configPaths(),
            baseline = baseline(),
            pluginPaths = pluginPaths(),
            buildUponDefaultConfig = settings.buildUponDefaultConfig,
            activateAllRules = settings.enableAllRules,
            enableFormatting = settings.enableFormatting,
            autoCorrect = autoCorrect,
            debug = redirect && settings.debug,
            outputChannel = if (redirect) SimpleAppendable { logger.info(it) } else null,
            errorChannel = if (redirect) SimpleAppendable { logger.warn(it) } else null,
            executor = DirectExecutor(),
        )
    }

    private fun configPaths(): List<Path> =
        settings.configurationFilePaths
            .asSequence()
            .filter { it.isNotBlank() }
            .asAbsolutePaths()
            .toList()

    private fun explicitPluginPaths(): List<Path> =
        settings.pluginJarPaths
            .asSequence()
            .filter { it.isNotBlank() }
            .asAbsolutePaths()
            .toList()

    private fun pluginJarsDirectory(): Path? =
        sequenceOf(settings.pluginJarsDirectory)
            .filter { it.isNotBlank() }
            .asAbsolutePaths()
            .firstOrNull()

    private fun pluginPaths(): List<Path> =
        (explicitPluginPaths() + jarsIn(pluginJarsDirectory())).distinct()

    private fun Sequence<String>.asAbsolutePaths() =
        map { it.replace('/', File.separatorChar) }
            .map { projectBasePath?.resolve(it) ?: Paths.get(it) }

    private fun baseline(): Path? = absoluteBaselinePath(project, settings)

    fun describeRules(): List<RuleDescription> = engine.describeRules(pluginPaths())

    fun fixTemplates(): Map<String, List<FixTemplate>> {
        val path = sequenceOf(settings.fixTemplatesPath).filter { it.isNotBlank() }.asAbsolutePaths().firstOrNull()
        if (path == null || !Files.isRegularFile(path)) return emptyMap()
        return runCatching { engine.loadFixTemplates(path) }
            .onFailure { logger.warn("Could not read fix templates from $path", it) }
            .getOrDefault(emptyMap())
    }

    fun suppressInBaseline(baselineId: String) {
        engine.suppressInBaseline(requireNotNull(baseline()), baselineId)
    }

    fun execute(file: PsiFile, autoCorrect: Boolean): AnalysisOutcome {
        val pathToAnalyze = file.virtualFile?.canonicalPath ?: return AnalysisOutcome.EMPTY
        if (!Files.isRegularFile(Path(pathToAnalyze))) return AnalysisOutcome.EMPTY
        val content = runCatching { ApplicationManager.getApplication().runReadAction(Computable { file.text }) }
            .onFailure {
                logErrorIfAllowed(
                    it,
                    "Unexpected error while reading file content: ${file.virtualFile.path}"
                )
            }
            .getOrThrow()
        return runCatching { execute(content, pathToAnalyze, autoCorrect) }
            .onFailure { logErrorIfAllowed(it, "Unexpected error while running detekt analysis") }
            .getOrDefault(AnalysisOutcome.EMPTY)
    }

    private fun logErrorIfAllowed(error: Throwable, message: String) {
        if (error is ProcessCanceledException) {
            return
        }
        logger.error(message, error)
    }

    fun execute(fileContent: String, filename: String, autoCorrect: Boolean): AnalysisOutcome {
        val normalizedFilename = filename.replace('\\', '/')
        if (SPECIAL_FILES_TO_IGNORE.any { normalizedFilename.startsWith(it) }) {
            return AnalysisOutcome.EMPTY
        }
        return engine.analyze(request(fileContent, filename, autoCorrect))
    }
}
