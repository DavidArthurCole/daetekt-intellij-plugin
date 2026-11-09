package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.gitlab.arturbosch.detekt.idea.util.restartAnalysis
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class DetektSources(private val project: Project) : Disposable {

    @Volatile
    private var directories: Set<Path>? = null

    @Volatile
    private var relativeDirectories: Set<Path> = emptySet()

    fun update(paths: Collection<String>, roots: Collection<String> = emptyList()) {
        val next = paths.map { Paths.get(it).normalize() }.toSet().ifEmpty { null }
        val rootPaths = roots.map { Paths.get(it).normalize() }
        val nextRelative = next.orEmpty().mapNotNull { dir ->
            rootPaths.filter { dir.startsWith(it) }.maxByOrNull { it.nameCount }?.relativize(dir)
        }.toSet()
        if (next == directories && nextRelative == relativeDirectories) return
        directories = next
        relativeDirectories = nextRelative
        ApplicationManager.getApplication().invokeLater({ restartAnalysis(project) }, project.disposed)
    }

    fun includes(file: Path, sourceRoot: () -> Path? = { null }): Boolean {
        val scope = directories ?: return true
        val parent = file.parent?.normalize() ?: return false
        if (parent in scope) return true
        if (relativeDirectories.isEmpty()) return false
        val root = sourceRoot()?.normalize() ?: return false
        return parent.startsWith(root) && root.relativize(parent) in relativeDirectories
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): DetektSources = project.service()
    }
}
