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

    fun update(paths: Collection<String>) {
        val next = paths.map { Paths.get(it).normalize() }.toSet().ifEmpty { null }
        if (next == directories) return
        directories = next
        ApplicationManager.getApplication().invokeLater({ restartAnalysis(project) }, project.disposed)
    }

    fun includes(file: Path): Boolean {
        val scope = directories ?: return true
        return file.parent?.normalize() in scope
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): DetektSources = project.service()
    }
}
