package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.components.service
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import io.gitlab.arturbosch.detekt.idea.engine.api.EngineFinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.deleteExisting

class DetektAnnotatorTest : KotlinParsingTestCase() {

    private val poko: Path = Paths.get(requireNotNull(javaClass.classLoader.getResource("testData/Poko.kt")).toURI())

    @Test
    fun `test returns empty list when detekt is disabled`() {
        assertThat(runAnnotator(enabled = false)).isEmpty()
    }

    @Test
    fun bundledDetektReportsEmptyClassOnPoko() {
        assertThat(runAnnotator(enabled = true).map { it.ruleId }).contains("EmptyClassBlock")
    }

    @Test
    fun fileMovedAfterParsingIsSkipped(@TempDir dir: Path) {
        project.service<DetektPluginSettings>().enableDetekt = true
        val moved = dir.resolve("Poko.kt")
        poko.copyTo(moved)
        val psi = parseFile(moved)
        moved.deleteExisting()

        assertThat(DetektAnnotator().doAnnotate(psi)).isEmpty()
    }

    private fun runAnnotator(enabled: Boolean): List<EngineFinding> {
        project.service<DetektPluginSettings>().enableDetekt = enabled
        return DetektAnnotator().doAnnotate(parseFile(poko))
    }
}
