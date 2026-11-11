package io.gitlab.arturbosch.detekt.idea.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProcessCanceledException
import io.gitlab.arturbosch.detekt.idea.engine.api.AnalysisOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MAX_LOG_BYTES = 5L * 1024 * 1024
private const val METRICS_WINDOW = 500
private const val NANOS_PER_MILLI = 1_000_000

internal object DetektLog {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

    val file: Path = Path.of(PathManager.getLogPath(), "daetekt.log")

    fun info(message: String) = write("INFO", message)

    fun warn(message: String) = write("WARN", message)

    @Synchronized
    private fun write(level: String, message: String) {
        if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
        runCatching {
            if (Files.exists(file) && Files.size(file) > MAX_LOG_BYTES) {
                Files.move(file, file.resolveSibling("daetekt.1.log"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                file,
                "${LocalDateTime.now().format(stamp)} $level $message${System.lineSeparator()}",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}

internal object DetektMetrics {

    class Summary(val runs: Long, val failures: Long, val window: Int, val median: Long, val p95: Long, val slowest: Long)

    private val durations = ArrayDeque<Long>()
    private var runs = 0L
    private var failures = 0L

    @Synchronized
    fun record(totalMillis: Long, failed: Boolean) {
        runs++
        if (failed) failures++
        durations.addLast(totalMillis)
        if (durations.size > METRICS_WINDOW) durations.removeFirst()
    }

    @Synchronized
    fun summary(): Summary? {
        if (durations.isEmpty()) return null
        val sorted = durations.sorted()
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
        return Summary(runs, failures, sorted.size, sorted[sorted.size / 2], p95, sorted.last())
    }
}

internal class RunTiming(private val file: String, private val autoCorrect: Boolean, private val rule: String?) {

    private val started = System.nanoTime()
    private var prepared = 0L
    private var classpath = 0
    var attempts = 0

    fun prepared(classpathSize: Int) {
        prepared = System.nanoTime()
        classpath = classpathSize
    }

    fun finish(outcome: AnalysisOutcome) = record(outcome.failure ?: outcome.configProblem, outcome.findings.size)

    fun fail(error: Throwable) {
        if (error !is ProcessCanceledException) record(error.toString(), 0)
    }

    private fun record(problem: String?, findings: Int) {
        if (prepared == 0L) return
        val now = System.nanoTime()
        val total = (now - started) / NANOS_PER_MILLI
        val engine = (now - prepared) / NANOS_PER_MILLI
        DetektMetrics.record(total, problem != null)
        val line = "analyze $file total=${total}ms prepare=${total - engine}ms engine=${engine}ms attempts=$attempts " +
            "classpath=$classpath findings=$findings autoCorrect=$autoCorrect rule=${rule ?: "-"}"
        if (problem == null) DetektLog.info(line) else DetektLog.warn("$line problem=$problem")
    }
}
