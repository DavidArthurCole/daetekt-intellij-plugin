package io.gitlab.arturbosch.detekt.idea

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Paths

class AnalysisBasePathTest {

    @Test
    fun fileInsideProjectUsesProjectAsBase() {
        val project = Paths.get("").toAbsolutePath()

        assertThat(analysisBasePath(project, project.resolve("src/Main.kt"))).isEqualTo(project)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun fileOnAnotherDriveUsesItsOwnFolderAsBase() {
        val project = Paths.get("C:\\work\\app")
        val input = Paths.get("Z:\\MPA\\app\\Generated.kt")

        assertThat(analysisBasePath(project, input)).isEqualTo(Paths.get("Z:\\MPA\\app"))
    }

    @Test
    fun missingProjectDirLeavesBaseUnset() {
        assertThat(analysisBasePath(null, Paths.get("").toAbsolutePath().resolve("Main.kt"))).isNull()
    }
}
