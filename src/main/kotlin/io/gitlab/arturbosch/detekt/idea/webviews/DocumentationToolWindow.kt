package io.gitlab.arturbosch.detekt.idea.webviews

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.jcef.JBCefApp

class DocumentationToolWindow : ToolWindowFactory, DumbAware {

    companion object {
        const val ID = "detekt doc"
        const val RULES_TAB = "Rules"
        const val DOCS_TAB = "detekt.dev"
    }

    override fun shouldBeAvailable(project: Project): Boolean = isJcefSupported()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val factory = contentManager.factory
        if (!isJcefSupported()) {
            val component = JBPanelWithEmptyText().apply {
                emptyText.text = "JCEF is not supported. Please use a (Jetbrains) JDK with JCEF enabled."
            }
            contentManager.addContent(factory.createContent(component, null, false))
            return
        }
        val browser = RulesBrowserService.getInstance()
        val docsContent = factory.createContent(browser.getComponent(), DOCS_TAB, false).apply { setDisposer(browser) }
        val rules = RulesView(project) { url ->
            if (isDetektDocs(url)) {
                browser.loadUrl(url)
                contentManager.setSelectedContent(docsContent)
            } else {
                BrowserUtil.browse(url)
            }
        }
        val rulesContent = factory.createContent(rules.component, RULES_TAB, false).apply { setDisposer(rules) }

        contentManager.addContent(rulesContent)
        contentManager.addContent(docsContent)
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content == rulesContent && event.operation == ContentManagerEvent.ContentOperation.add) {
                    rules.reload()
                }
            }
        })
        contentManager.setSelectedContent(rulesContent)
        rules.reload()
    }
}

internal fun isJcefSupported(): Boolean = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
