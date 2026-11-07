package io.gitlab.arturbosch.detekt.idea.config.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile

object FileChooserDescriptorUtil {

    fun createJarsChooserDescriptor(): FileChooserDescriptor =
        FileChooserDescriptor(true, false, true, true, false, true)
            .withFileFilter { it.hasExtension("jar") }

    fun createYamlChooserDescriptor(): FileChooserDescriptor =
        FileChooserDescriptor(true, false, false, false, false, true)
            .withFileFilter { it.hasExtension("yml", "yaml") }

    fun createSingleFolderChooserDescriptor(): FileChooserDescriptor =
        FileChooserDescriptor(false, true, false, false, false, false)

    fun createSingleXmlChooserDescriptor(): FileChooserDescriptor =
        FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { it.hasExtension("xml") }

    private fun VirtualFile.hasExtension(vararg extensions: String): Boolean =
        extensions.any { extension.equals(it, ignoreCase = true) }
}
