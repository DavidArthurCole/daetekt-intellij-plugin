package io.gitlab.arturbosch.detekt.idea.engine

import io.gitlab.arturbosch.detekt.idea.DETEKT
import io.gitlab.arturbosch.detekt.idea.engine.api.DetektEngine
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ServiceLoader
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

const val DETEKT_RUNTIME_PROPERTY = "intellijekt.detekt.runtime"

private const val ENGINE_API_PACKAGE = "io.gitlab.arturbosch.detekt.idea.engine.api."

object EngineLoader {

    val engine: DetektEngine by lazy { load(runtimeDirectory(), EngineLoader::class.java.classLoader) }

    fun load(runtime: Path, apiLoader: ClassLoader): DetektEngine {
        val loader = EngineClassLoader(runtimeJars(runtime), apiLoader)
        return ServiceLoader.load(DetektEngine::class.java, loader).single()
    }

    internal fun runtimeJars(runtime: Path): List<Path> {
        require(runtime.isDirectory()) { "detekt runtime directory $runtime does not exist." }
        return Files.list(runtime).use { paths -> paths.filter { it.extension == "jar" }.sorted().toList() }
    }

    private fun runtimeDirectory(): Path {
        System.getProperty(DETEKT_RUNTIME_PROPERTY)?.let { return Path(it) }
        return pluginRoot(ownJar()).resolve(DETEKT)
    }

    private fun ownJar(): Path {
        val anchor = EngineLoader::class.java.name.replace('.', '/') + ".class"
        val url = requireNotNull(EngineLoader::class.java.classLoader.getResource(anchor)) { "Missing $anchor" }
        return Paths.get(URI(url.toString().removePrefix("jar:").substringBefore("!/")))
    }

    internal fun pluginRoot(pluginJar: Path): Path = pluginJar.parent.parent
}

internal class EngineClassLoader(jars: List<Path>, private val apiLoader: ClassLoader) :
    URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), getPlatformClassLoader()) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith(ENGINE_API_PACKAGE)) {
            return apiLoader.loadClass(name)
        }
        return super.loadClass(name, resolve)
    }
}
