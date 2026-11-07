package io.gitlab.arturbosch.detekt.idea.util

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.IconLoader
import java.util.jar.Manifest
import javax.swing.Icon

object PluginUtils {

    fun pluginIcon(): Icon = IconLoader.getIcon("META-INF/pluginIconSmall.svg", PluginUtils::class.java.classLoader)

    fun platformVersion(): String = ApplicationInfo.getInstance().fullVersion

    fun pluginVersion(): String =
        PluginUtils::class.java.classLoader.getResources("META-INF/MANIFEST.MF").asSequence()
            .mapNotNull { url -> url.openStream().use { Manifest(it) }.mainAttributes }
            .firstOrNull { it.getValue("Build-Plugin") == "IntelliJ Platform Gradle Plugin" }
            ?.getValue("Version")
            ?: "unknown"
}
