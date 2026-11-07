package io.gitlab.arturbosch.detekt.idea

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

class OptionalModuleBoundaryTest {

    private val compiledClasses: Map<String, String> = readPluginClasses()

    @Test
    fun onlyWebviewClassesReferenceJcef() {
        val jcefUsers = classesReferencing("com/intellij/ui/jcef/") + classesReferencing("org/cef/")

        assertThat(jcefUsers).isNotEmpty
        assertThat(jcefUsers).allMatch { it.startsWith("io/gitlab/arturbosch/detekt/idea/webviews/") }
    }

    @Test
    fun noClassReferencesVcsModule() {
        assertThat(compiledClasses).isNotEmpty
        assertThat(classesReferencing("com/intellij/openapi/vcs/")).isEmpty()
    }

    @Test
    fun webviewsAreRegisteredOnlyUnderOptionalJcefDependency() {
        val main = pluginDescriptor()
        val jcef = main.elements("depends").single { it.textContent.trim() == "com.intellij.modules.jcef" }

        assertThat(jcef.getAttribute("optional")).isEqualTo("true")
        assertThat(main.webviewReferences()).isEmpty()
        assertThat(readDescriptor("META-INF/${jcef.getAttribute("config-file")}").webviewReferences())
            .containsExactlyInAnyOrder(
                "io.gitlab.arturbosch.detekt.idea.webviews.DocumentationToolWindow",
                "io.gitlab.arturbosch.detekt.idea.webviews.ShowRulesAction",
                "io.gitlab.arturbosch.detekt.idea.webviews.ShowChangelogAction",
            )
    }

    private fun classesReferencing(internalPackage: String): Set<String> =
        compiledClasses.filterValues { it.contains(internalPackage) }.keys

    private fun readPluginClasses(): Map<String, String> {
        val location = Paths.get(DetektAnnotator::class.java.protectionDomain.codeSource.location.toURI())
        if (location.isRegularFile()) {
            return FileSystems.newFileSystem(location).use { readClasses(it.getPath("/")) }
        }
        return readClasses(location)
    }

    private fun readClasses(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { it.extension == "class" }
            .toList()
            .associate { root.relativize(it).invariantSeparatorsPathString to it.readBytes().toString(Charsets.ISO_8859_1) }
    }

    private fun pluginDescriptor(): Element =
        javaClass.classLoader.getResources("META-INF/plugin.xml").toList()
            .map { url -> url.openStream().use(::parse) }
            .single { it.elements("id").firstOrNull()?.textContent?.trim() == DETEKT }

    private fun readDescriptor(resource: String): Element {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "Missing $resource" }
        return stream.use(::parse)
    }

    private fun parse(stream: InputStream): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.webviewReferences(): List<String> {
        val toolWindows = elements("toolWindow").map { it.getAttribute("factoryClass") }
        val actions = elements("action").map { it.getAttribute("class") }
        return (toolWindows + actions).filter { it.contains(".webviews.") }
    }
}
