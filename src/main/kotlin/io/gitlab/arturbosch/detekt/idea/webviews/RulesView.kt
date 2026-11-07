package io.gitlab.arturbosch.detekt.idea.webviews

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import io.gitlab.arturbosch.detekt.idea.rules.RulesTheme
import io.gitlab.arturbosch.detekt.idea.rules.rulesPage
import javax.swing.JComponent

internal class RulesView(
    private val project: Project,
    private val openUrl: (String) -> Unit,
) : Disposable {

    private val logger = logger<RulesView>()
    private val browser = JBCefBrowser()
    private val linkQuery = browserBase().let { JBCefJSQuery.create(it) }
    private var rules: List<RuleDescription>? = null

    init {
        Disposer.register(this, browser)
        Disposer.register(this, linkQuery)
        linkQuery.addHandler { url ->
            ApplicationManager.getApplication().invokeLater { openUrl(url) }
            null
        }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { render() })
        browser.loadHTML(placeholder("Loading detekt rules..."))
    }

    val component: JComponent
        get() = browser.component

    private fun browserBase(): JBCefBrowserBase = browser

    fun reload() {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val loaded = runCatching { ConfiguredService(project).describeRules() }
            application.invokeLater({
                loaded
                    .onSuccess {
                        rules = it
                        render()
                    }
                    .onFailure {
                        logger.warn("Could not load detekt rules", it)
                        browser.loadHTML(placeholder("Could not load detekt rules: ${it.message}"))
                    }
            }, project.disposed)
        }
    }

    private fun render() {
        val current = rules ?: return
        browser.loadHTML(rulesPage(current, theme(), linkQuery.inject("url")))
    }

    private fun placeholder(message: String): String {
        val theme = theme()
        return rulesPage(emptyList(), theme, "").replace("No detekt rules loaded.", message)
    }

    private fun theme(): RulesTheme {
        val font = UIUtil.getLabelFont()
        return RulesTheme(
            background = UIUtil.getPanelBackground(),
            foreground = UIUtil.getLabelForeground(),
            muted = UIUtil.getContextHelpForeground(),
            accent = JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6)),
            surface = JBColor.namedColor("TextField.background", UIUtil.getPanelBackground()),
            border = JBColor.border(),
            fontFamily = font.family,
            fontSize = font.size,
        )
    }

    override fun dispose() = Unit
}
