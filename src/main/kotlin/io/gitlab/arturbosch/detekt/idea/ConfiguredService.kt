package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.EngineLoader
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisOutcome
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisRequest
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import io.gitlab.arturbosch.detekt.idea.engine.api.RulesRequest
import io.gitlab.arturbosch.detekt.idea.intention.RuleRef
import io.gitlab.arturbosch.detekt.idea.util.DirectExecutor
import io.gitlab.arturbosch.detekt.idea.util.SimpleAppendable
import io.gitlab.arturbosch.detekt.idea.util.absoluteBaselinePath
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.Path
import kotlin.io.path.exists

private const val CLASSPATH_RACE_ATTEMPTS = 2
private const val PREVIEW_CACHE_LIMIT = 32

private val previewCache = ConcurrentHashMap<String, Future<String?>>()

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

    private fun request(
        content: String,
        filename: String,
        autoCorrect: Boolean,
        compile: CompileContext,
        only: RuleRef?,
    ): AnalysisRequest {
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
            classpath = compile.classpath,
            jdkHome = compile.jdkHome,
            onlyRuleSetId = only?.ruleSetId,
            onlyRuleId = only?.ruleId,
        )
    }

    private fun compileContext(file: PsiFile): CompileContext =
        ApplicationManager.getApplication().runReadAction(Computable {
            val virtualFile = file.virtualFile
            val module = virtualFile?.let { project.getService(ProjectFileIndex::class.java)?.getModuleForFile(it) }
            if (module == null) {
                CompileContext.NONE
            } else {
                CachedValuesManager.getManager(project).getCachedValue(module) {
                    CachedValueProvider.Result.create(
                        CompileContext(
                            classpath = OrderEnumerator.orderEntries(module).recursively().withoutSdk().classes()
                                .pathsList.pathList.map { Path(it) }.filter { Files.exists(it) },
                            jdkHome = ModuleRootManager.getInstance(module).sdk?.homePath?.let { Path(it) },
                        ),
                        ProjectRootManager.getInstance(project),
                    )
                }
            }
        })

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

    fun describeRules(): List<RuleDescription> = engine.describeRules(
        RulesRequest(
            pluginPaths = pluginPaths(),
            configPaths = configPaths(),
            buildUponDefaultConfig = settings.buildUponDefaultConfig,
            activateAllRules = settings.enableAllRules,
            enableFormatting = settings.enableFormatting,
        )
    )

    fun fixTemplates(): Map<String, List<FixTemplate>> {
        val path = sequenceOf(settings.fixTemplatesPath).filter { it.isNotBlank() }.asAbsolutePaths().firstOrNull()
        if (path == null || !Files.isRegularFile(path)) return emptyMap()
        val stamp = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        templateCache.get()?.takeIf { it.path == path && it.stamp == stamp }?.let { return it.templates }
        val templates = runCatching { engine.loadFixTemplates(path) }
            .onFailure { logger.warn("Could not read fix templates from $path", it) }
            .getOrDefault(emptyMap())
        templateCache.set(CachedTemplates(path, stamp, templates))
        return templates
    }

    fun suppressInBaseline(baselineId: String) {
        engine.suppressInBaseline(requireNotNull(baseline()), baselineId)
    }

    fun execute(file: PsiFile, autoCorrect: Boolean, only: RuleRef? = null, providedContent: String? = null): AnalysisOutcome {
        val pathToAnalyze = file.virtualFile?.canonicalPath ?: return AnalysisOutcome.EMPTY
        if (!Files.isRegularFile(Path(pathToAnalyze))) return AnalysisOutcome.EMPTY
        val sourceRoot = {
            ApplicationManager.getApplication().runReadAction(Computable {
                file.virtualFile?.let { project.getService(ProjectFileIndex::class.java)?.getSourceRootForFile(it) }?.canonicalPath
            })?.let(::Path)
        }
        if (!DetektSources.getInstance(project).includes(Path(pathToAnalyze), sourceRoot)) {
            return failed(DetektBundle.message("detekt.analysis.outsideSources"))
        }
        val content = providedContent ?: runCatching { ApplicationManager.getApplication().runReadAction(Computable { file.text }) }
            .onFailure {
                logErrorIfAllowed(
                    it,
                    "Unexpected error while reading file content: ${file.virtualFile.path}"
                )
            }
            .getOrThrow()
        val compile = compileContext(file)
        repeat(CLASSPATH_RACE_ATTEMPTS) { attempt ->
            val result = runCatching { execute(content, pathToAnalyze, autoCorrect, compile, only) }
            val error = result.exceptionOrNull() ?: return result.getOrThrow()
            if (error is ProcessCanceledException) throw error
            if (!error.touchesClasspath(compile)) {
                logErrorIfAllowed(error, "Unexpected error while running detekt analysis")
                return failed(DetektBundle.message("detekt.analysis.failed", error.rootMessage()))
            }
            logger.info("detekt run ${attempt + 1} hit a classpath file that changed during analysis", error)
        }
        return failed(DetektBundle.message("detekt.analysis.classpathChanged"))
    }

    private fun failed(reason: String) = AnalysisOutcome(emptyList(), null, null, reason)

    private fun Throwable.rootMessage(): String =
        generateSequence(this) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }

    fun previewCorrection(file: PsiFile, content: String, only: RuleRef?): String? {
        val path = file.virtualFile?.canonicalPath ?: return null
        val compile = compileContext(file)
        val key = "$path|${only?.ruleSetId}:${only?.ruleId}|${content.length}|${content.hashCode()}"
        if (previewCache.size > PREVIEW_CACHE_LIMIT) previewCache.clear()
        val future = previewCache.computeIfAbsent(key) {
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runCatching { execute(content, path, true, compile, only).correctedContent }.getOrNull()
            })
        }
        return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
    }

    private fun Throwable.touchesClasspath(compile: CompileContext): Boolean =
        generateSequence(this) { it.cause }.any { error ->
            val missing = ((error as? NoSuchFileException)?.file ?: (error as? FileNotFoundException)?.message)
                ?.replace('/', '\\')
            missing != null && compile.classpath.any { missing.startsWith(it.toString().replace('/', '\\'), ignoreCase = true) }
        }

    private fun logErrorIfAllowed(error: Throwable, message: String) {
        if (error is ProcessCanceledException) {
            return
        }
        logger.error(message, error)
    }

    fun execute(
        fileContent: String,
        filename: String,
        autoCorrect: Boolean,
        compile: CompileContext = CompileContext.NONE,
        only: RuleRef? = null,
    ): AnalysisOutcome {
        val normalizedFilename = filename.replace('\\', '/')
        if (SPECIAL_FILES_TO_IGNORE.any { normalizedFilename.startsWith(it) }) {
            return AnalysisOutcome.EMPTY
        }
        return engine.analyze(request(fileContent, filename, autoCorrect, compile, only))
    }
}

private class CachedTemplates(val path: Path, val stamp: Long, val templates: Map<String, List<FixTemplate>>)

private val templateCache = AtomicReference<CachedTemplates?>()

class CompileContext(val classpath: List<Path>, val jdkHome: Path?) {

    companion object {
        val NONE = CompileContext(emptyList(), null)
    }
}
