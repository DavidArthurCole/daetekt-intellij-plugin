package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.components.service
import io.gitlab.arturbosch.detekt.idea.config.DetektPluginSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

class ConfiguredServiceTest : MockProjectTestCase() {

    @Test
    fun `non configured plugin has no problems`() {
        val service = ConfiguredService(project, FakeDetektEngine())

        val problems = service.validate()

        assertThat(problems).isEmpty()
    }

    @Test
    fun `invalid config found`() {
        val settings = project.service<DetektPluginSettings>()
        settings.configurationFilePaths = mutableListOf("example")

        val problems = ConfiguredService(project, FakeDetektEngine()).validate()

        assertThat(problems).hasSize(1)
        assertThat(problems.first()).contains("Configuration file")
        assertThat(problems.first()).contains("does not exist")
    }

    @Test
    fun `invalid baseline file found`() {
        val settings = project.service<DetektPluginSettings>()
        settings.baselinePath = "example"

        val problems = ConfiguredService(project, FakeDetektEngine()).validate()

        assertThat(problems).hasSize(1)
        assertThat(problems.first()).contains("baseline file")
        assertThat(problems.first()).contains("does not exist")
    }

    @Test
    fun `invalid plugin found`() {
        val settings = project.service<DetektPluginSettings>()
        settings.pluginJarPaths = mutableListOf("example")

        val problems = ConfiguredService(project, FakeDetektEngine()).validate()

        assertThat(problems).hasSize(1)
        assertThat(problems.first()).contains("Plugin jar")
        assertThat(problems.first()).contains("does not exist")
    }

    @Test
    fun specialFilesNeverReachTheEngine() {
        val engine = FakeDetektEngine()
        val service = ConfiguredService(project, engine)

        service.execute("", SPECIAL_FILENAME_FOR_DEBUGGING, false)
        service.execute("", SPECIAL_FILENAME_AI_SNIPPED, false)
        service.execute("val x = 1", "\\AIAssistantSnippet..", false)
        service.execute("val x = 1", "\\AIAssistantSnippet.kt", false)

        assertThat(engine.requests).isEmpty()
    }

    @Test
    fun everyJarInPluginDirectoryReachesTheEngineAlongsideExplicitJars(@TempDir dir: Path) {
        val pluginDir = dir.resolve("detekt-ide-plugins").createDirectories()
        val rules = pluginDir.resolve("skyhanni-rules.jar").createFile()
        val neu = pluginDir.resolve("detektrules-1.0.0.jar").createFile()
        pluginDir.resolve("README.txt").createFile()
        val explicit = dir.resolve("extra.jar").createFile()
        val settings = project.service<DetektPluginSettings>()
        settings.pluginJarsDirectory = pluginDir.toString()
        settings.pluginJarPaths = listOf(explicit.toString(), rules.toString())
        val engine = FakeDetektEngine()

        ConfiguredService(project, engine).execute("val x = 1", "Main.kt", autoCorrect = false)

        assertThat(engine.requests.single().pluginPaths).containsExactlyInAnyOrder(explicit, rules, neu)
    }

    @Test
    fun missingPluginDirectoryIsAProblem(@TempDir dir: Path) {
        project.service<DetektPluginSettings>().pluginJarsDirectory = dir.resolve("not-built-yet").toString()

        val problems = ConfiguredService(project, FakeDetektEngine()).validate()

        assertThat(problems.single()).contains("Plugin jars directory").contains("does not exist")
    }

    @Test
    fun formattingAndRuleTogglesReachTheEngine() {
        val settings = project.service<DetektPluginSettings>()
        settings.enableFormatting = true
        settings.enableAllRules = true
        settings.buildUponDefaultConfig = false
        val engine = FakeDetektEngine()

        ConfiguredService(project, engine).execute("val x = 1", "Main.kt", autoCorrect = true)

        val request = engine.requests.single()
        assertThat(request.enableFormatting).isTrue()
        assertThat(request.activateAllRules).isTrue()
        assertThat(request.buildUponDefaultConfig).isFalse()
        assertThat(request.autoCorrect).isTrue()
    }
}
