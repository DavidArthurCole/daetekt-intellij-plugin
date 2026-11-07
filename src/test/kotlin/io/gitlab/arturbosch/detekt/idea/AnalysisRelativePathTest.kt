package io.gitlab.arturbosch.detekt.idea

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Paths

class AnalysisRelativePathTest {

    private val project = Paths.get("").toAbsolutePath()

    @Test
    fun fileInsideProjectKeepsProjectRelativePathForConfigExcludes() {
        val input = project.resolve("src/test/kotlin/Sample.kt")

        assertThat(analysisRelativePath(project, input)).isEqualTo(Paths.get("src/test/kotlin/Sample.kt"))
    }

    @Test
    fun fileOutsideProjectFallsBackToFileName() {
        val input = project.parent.resolve("elsewhere/Generated.kt")

        assertThat(analysisRelativePath(project, input)).isEqualTo(Paths.get("Generated.kt"))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun fileOnAnotherDriveFallsBackToFileName() {
        val input = Paths.get("Z:\\MPA\\app\\Generated.kt")

        assertThat(analysisRelativePath(Paths.get("C:\\work\\app"), input)).isEqualTo(Paths.get("Generated.kt"))
    }

    @Test
    fun missingProjectDirFallsBackToFileName() {
        assertThat(analysisRelativePath(null, project.resolve("src/Main.kt"))).isEqualTo(Paths.get("Main.kt"))
    }
}
