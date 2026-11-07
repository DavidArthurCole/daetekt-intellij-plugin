package io.gitlab.arturbosch.detekt.idea.problems

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.openapi.vfs.VirtualFile
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineSeverity
import javax.swing.Icon

class DetektProblem(
    override val provider: DetektProblemsProvider,
    override val file: VirtualFile,
    private val finding: EngineFinding,
) : FileProblem {

    override val text: String
        get() = finding.message

    override val description: String
        get() = finding.description

    override val group: String
        get() = finding.ruleId

    override val icon: Icon
        get() = when (finding.severity) {
            EngineSeverity.Error -> HighlightDisplayLevel.ERROR.icon
            EngineSeverity.Warning -> HighlightDisplayLevel.WARNING.icon
            EngineSeverity.Info -> HighlightDisplayLevel.WEAK_WARNING.icon
        }

    override val line: Int
        get() = finding.line - 1

    override val column: Int
        get() = finding.column - 1
}
