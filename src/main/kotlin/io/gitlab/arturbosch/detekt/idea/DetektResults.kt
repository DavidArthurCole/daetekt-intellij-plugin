package io.gitlab.arturbosch.detekt.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object DetektResults {

    private class Entry(val stamp: Long, val generation: Long, val annotations: DetektAnnotations)

    private val done = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, String>()
    private val generation = AtomicLong()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun resultFor(file: PsiFile, stamp: Long, compute: () -> DetektAnnotations): DetektAnnotations {
        val application = ApplicationManager.getApplication()
        val path = file.virtualFile?.path
        if (application.isUnitTestMode || path == null) return compute()
        val current = generation.get()
        done[path]?.takeIf { it.stamp == stamp && it.generation == current }?.let { return it.annotations }
        val token = "$stamp:$current"
        if (pending.put(path, token) == token) return DetektAnnotations.EMPTY
        val project = file.project
        application.executeOnPooledThread {
            val annotations = runCatching { compute() }.getOrDefault(DetektAnnotations.EMPTY)
            if (pending.remove(path, token)) {
                done[path] = Entry(stamp, current, annotations)
                application.invokeLater({
                    if (file.isValid) DaemonCodeAnalyzer.getInstance(project).restart(file)
                }, project.disposed)
            }
        }
        return DetektAnnotations.EMPTY
    }
}
