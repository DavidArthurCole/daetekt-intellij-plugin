package io.gitlab.arturbosch.detekt.idea.webviews

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import io.gitlab.arturbosch.detekt.idea.ConfiguredService
import io.gitlab.arturbosch.detekt.idea.rules.RuleEntry
import io.gitlab.arturbosch.detekt.idea.rules.RuleSource
import io.gitlab.arturbosch.detekt.idea.rules.RulesTheme
import io.gitlab.arturbosch.detekt.idea.rules.locateRuleSources
import io.gitlab.arturbosch.detekt.idea.rules.rulesPage
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

internal class RulesView(
    private val project: Project,
    private val openUrl: (String) -> Unit,
) : Disposable {

    private val logger = logger<RulesView>()
    private val browser = JBCefBrowser()
    private val bridge = browserBase().let { JBCefJSQuery.create(it) }
    private var entries: List<RuleEntry>? = null
    private var sources: Map<String, RuleSource> = emptyMap()
    @Volatile
    private var viewState: String? = null

    init {
        Disposer.register(this, browser)
        Disposer.register(this, bridge)
        bridge.addHandler { message ->
            handle(message.substringBefore('\n'), message.substringAfter('\n', ""))
            null
        }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { render() })
        show(emptyList(), "Loading detekt rules...")
    }

    val component: JComponent
        get() = browser.component

    private fun browserBase(): JBCefBrowserBase = browser

    private fun handle(type: String, payload: String) {
        if (type == "state") {
            viewState = payload
            return
        }
        ApplicationManager.getApplication().invokeLater({
            when (type) {
                "open" -> openUrl(payload)
                "source" -> sources[payload]?.navigate(project)
                "copy" -> CopyPasteManager.getInstance().setContents(StringSelection(payload))
                "reload" -> reload()
            }
        }, project.disposed)
    }

    fun reload() {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val loaded = runCatching {
                val rules = ConfiguredService(project).describeRules()
                val located = runCatching { locateRuleSources(project, rules.filter { it.custom }.mapNotNull { it.className }) }
                    .onFailure { logger.warn("Could not locate custom rule sources", it) }
                    .getOrDefault(emptyMap())
                rules.map { RuleEntry(it, it.className?.let(located::get)) } to located
            }
            application.invokeLater({
                loaded
                    .onSuccess { (rules, located) ->
                        entries = rules
                        sources = located
                        render()
                    }
                    .onFailure {
                        logger.warn("Could not load detekt rules", it)
                        show(emptyList(), "Could not load detekt rules: ${it.message}")
                    }
            }, project.disposed)
        }
    }

    private fun render() {
        show(entries ?: return, null)
    }

    private fun show(rules: List<RuleEntry>, message: String?) {
        browser.loadHTML(rulesPage(rules, theme(), bridge.inject("message"), message, viewState))
    }

    private fun theme(): RulesTheme {
        val font = UIUtil.getLabelFont()
        val scheme = EditorColorsManager.getInstance().globalScheme
        return RulesTheme(
            dark = !JBColor.isBright(),
            background = UIUtil.getPanelBackground(),
            foreground = UIUtil.getLabelForeground(),
            muted = UIUtil.getContextHelpForeground(),
            accent = JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6)),
            surface = JBColor.namedColor("TextField.background", UIUtil.getPanelBackground()),
            border = JBColor.border(),
            codeBackground = scheme.defaultBackground,
            fontFamily = font.family,
            fontSize = font.size,
            codeFontFamily = scheme.editorFontName,
            codeFontSize = scheme.editorFontSize,
        )
    }

    override fun dispose() = Unit
}
