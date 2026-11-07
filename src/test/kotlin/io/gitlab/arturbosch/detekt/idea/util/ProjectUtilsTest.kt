package io.gitlab.arturbosch.detekt.idea.util

import com.intellij.mock.MockVirtualFile
import io.gitlab.arturbosch.detekt.idea.MockProjectTestCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectUtilsTest : MockProjectTestCase() {

    @BeforeEach
    fun resetShownProblems() {
        clearShownProblems(project)
    }

    @Test
    fun sameProblemsAreShownOnceAcrossPasses() {
        val problems = listOf("Plugin jar <b>compose.jar</b> does not exist.")

        assertThat(markProblemsShown(problems, project)).isTrue()
        assertThat(markProblemsShown(problems.toList(), project)).isFalse()
    }

    @Test
    fun changedProblemsAreShownAgain() {
        markProblemsShown(listOf("Plugin jar <b>a.jar</b> does not exist."), project)

        assertThat(markProblemsShown(listOf("Plugin jar <b>b.jar</b> does not exist."), project)).isTrue()
    }

    @Test
    fun problemsReturningAfterCleanPassAreShownAgain() {
        val problems = listOf("Configuration file <b>detekt.yml</b> does not exist.")
        markProblemsShown(problems, project)

        clearShownProblems(project)

        assertThat(markProblemsShown(problems, project)).isTrue()
    }

    @Test
    fun removedJarStillStoredInSettingsCountsAsUnresolved() {
        val jar = MockVirtualFile.file("rules.jar")
        val resolve = { path: String -> jar.takeIf { path == "/libs/rules.jar" } }

        assertThat(hasUnresolvedPaths(listOf("/libs/rules.jar"), resolve)).isFalse()
        assertThat(hasUnresolvedPaths(listOf("/libs/rules.jar", "/libs/detekt-compose.jar"), resolve)).isTrue()
        assertThat(hasUnresolvedPaths(listOf(" "), resolve)).isTrue()
    }
}
