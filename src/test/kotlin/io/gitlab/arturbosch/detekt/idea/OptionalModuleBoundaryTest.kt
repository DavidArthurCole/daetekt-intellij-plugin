package io.gitlab.arturbosch.detekt.idea

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readBytes

class OptionalModuleBoundaryTest {

    private val compiledClasses = mutableMapOf<String, String>()
    private val descriptors = mutableMapOf<String, Element>()

    init {
        withPluginRoot { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.extension == "class" }.forEach {
                    compiledClasses[root.relativize(it).invariantSeparatorsPathString] =
                        it.readBytes().toString(Charsets.ISO_8859_1)
                }
            }
            Files.list(root.resolve("META-INF")).use { paths ->
                paths.filter { it.extension == "xml" }.forEach {
                    descriptors[it.fileName.toString()] = it.inputStream().use { stream ->
                        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
                    }
                }
            }
        }
    }

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
        val main = descriptors.getValue("plugin.xml")
        val jcef = main.elements("depends").single { it.textContent.trim() == "com.intellij.modules.jcef" }

        assertThat(jcef.getAttribute("optional")).isEqualTo("true")
        assertThat(main.webviewReferences()).isEmpty()
        assertThat(descriptors.getValue(jcef.getAttribute("config-file")).webviewReferences())
            .containsExactlyInAnyOrder(
                "io.gitlab.arturbosch.detekt.idea.webviews.DocumentationToolWindow",
                "io.gitlab.arturbosch.detekt.idea.webviews.ShowRulesAction",
                "io.gitlab.arturbosch.detekt.idea.webviews.ShowChangelogAction",
            )
    }

    private fun classesReferencing(internalPackage: String): Set<String> =
        compiledClasses.filterValues { it.contains(internalPackage) }.keys

    private fun withPluginRoot(block: (Path) -> Unit) {
        val anchor = DetektAnnotator::class.java.name.replace('.', '/') + ".class"
        val url = requireNotNull(DetektAnnotator::class.java.classLoader.getResource(anchor)) { "Missing $anchor" }
        if (url.protocol == "jar") {
            val jar = Paths.get(URI(url.toString().removePrefix("jar:").substringBefore("!/")))
            FileSystems.newFileSystem(jar).use { block(it.getPath("/")) }
            return
        }
        block(anchor.split('/').fold(Paths.get(url.toURI())) { path, _ -> path.parent })
    }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.webviewReferences(): List<String> {
        val toolWindows = elements("toolWindow").map { it.getAttribute("factoryClass") }
        val actions = elements("action").map { it.getAttribute("class") }
        return (toolWindows + actions).filter { it.contains(".webviews.") }
    }
}
