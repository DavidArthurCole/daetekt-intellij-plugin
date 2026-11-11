package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.gitlab.arturbosch.detekt.idea.util.DetektLog
import io.gitlab.arturbosch.detekt.idea.util.restartHighlighting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val NANOS_PER_MILLI = 1_000_000
private const val DONE_LIMIT = 256
private const val MAX_RETRIES = 6
private const val RETRY_DELAY_SECONDS = 10L

internal object DetektResults {

    private class Entry(val stamp: Long, val generation: Long, val annotations: DetektAnnotations)

    private val done = ConcurrentHashMap<String, Entry>()
    private val wanted = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retries = ConcurrentHashMap<String, Int>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val generation = AtomicLong()
    private val running = AtomicInteger()

    fun invalidate() {
        generation.incrementAndGet()
        retries.clear()
        retryAfter.clear()
    }

    fun resultFor(file: PsiFile, stamp: Long, compute: () -> DetektAnnotations): DetektAnnotations {
        val application = ApplicationManager.getApplication()
        val path = file.virtualFile?.path
        if (application.isUnitTestMode || path == null) return compute()
        val current = generation.get()
        done[path]?.takeIf { it.stamp == stamp && it.generation == current }?.let { return it.annotations }
        val token = "$stamp:$current"
        wanted[path] = token
        if (retryAfter[path].let { it != null && it > System.nanoTime() }) return DetektAnnotations.EMPTY
        if (!inFlight.add(path)) return DetektAnnotations.EMPTY
        val queued = System.nanoTime()
        application.executeOnPooledThread {
            val started = System.nanoTime()
            val concurrent = running.incrementAndGet()
            val annotations = runCatching { compute() }.getOrDefault(DetektAnnotations.EMPTY)
            running.decrementAndGet()
            inFlight.remove(path)
            val latest = wanted[path] == token
            val retry = latest && annotations.retry && retries.merge(path, 1, Int::plus)!! <= MAX_RETRIES
            DetektLog.info(
                "annotate ${path.substringAfterLast('/')} wait=${(started - queued) / NANOS_PER_MILLI}ms " +
                    "run=${(System.nanoTime() - started) / NANOS_PER_MILLI}ms concurrent=$concurrent " +
                    "latest=$latest retry=$retry"
            )
            val restart = {
                application.invokeLater({
                    if (file.isValid) restartHighlighting(file, "detekt results ready")
                }, file.project.disposed)
            }
            if (retry) {
                retryAfter[path] = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_DELAY_SECONDS)
                AppExecutorUtil.getAppScheduledExecutorService().schedule(restart, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                return@executeOnPooledThread
            }
            if (latest) {
                retries.remove(path)
                retryAfter.remove(path)
                if (done.size >= DONE_LIMIT) done.clear()
                done[path] = Entry(stamp, current, annotations)
            }
            restart()
        }
        return DetektAnnotations.EMPTY
    }
}
