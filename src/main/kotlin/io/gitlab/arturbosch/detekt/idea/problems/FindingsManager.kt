package io.gitlab.arturbosch.detekt.idea.problems

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class FindingsManager : Disposable {

    companion object {

        fun getInstance(project: Project): FindingsManager = project.service()
    }

    private val listeners = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val state = ConcurrentHashMap<VirtualFile, List<EngineFinding>>()

    fun register(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    fun getAllFindingsSize(): Int = state.values.fold(0) { acc, cur -> acc + cur.size }

    fun getFindings(file: VirtualFile): List<EngineFinding> = state[file]?.toList() ?: emptyList()

    fun getAnalyzedFiles(): Collection<VirtualFile> = state.keys.toList()

    fun put(file: VirtualFile, findings: List<EngineFinding>) {
        state[file] = findings
    }

    override fun dispose() {
        listeners.clear()
        state.clear()
    }
}
