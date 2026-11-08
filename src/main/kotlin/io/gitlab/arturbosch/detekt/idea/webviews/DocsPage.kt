package io.gitlab.arturbosch.detekt.idea.webviews

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import java.net.URI

enum class DocsPage(val url: String) {
    Rules("https://detekt.dev/docs/rules/style"),
    Changelog("https://detekt.dev/changelog"),
}

internal fun isDetektDocs(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "detekt.dev" || host.endsWith(".detekt.dev")
}

fun showPage(page: DocsPage, project: Project) {
    withDocsWindow(project) { toolWindow ->
        toolWindow.select(DocumentationToolWindow.DOCS_TAB)
        RulesBrowserService.getInstance().loadPage(page)
    }
}

fun showRules(project: Project) {
    withDocsWindow(project) { toolWindow -> toolWindow.select(DocumentationToolWindow.RULES_TAB) }
}

private fun withDocsWindow(project: Project, block: (ToolWindow) -> Unit) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DocumentationToolWindow.ID) ?: return
    toolWindow.show { block(toolWindow) }
}

private fun ToolWindow.select(tab: String) {
    contentManager.findContent(tab)?.let { contentManager.setSelectedContent(it) }
}
