package io.gitlab.arturbosch.detekt.idea.rules

import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import java.awt.Color

internal class RulesTheme(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val surface: Color,
    val border: Color,
    val fontFamily: String,
    val fontSize: Int,
)

internal fun RuleDescription.matches(query: String): Boolean =
    query.isEmpty() || listOfNotNull(ruleId, ruleSetId, description, source).any { it.contains(query, ignoreCase = true) }

internal fun RuleDescription.documentationUrl(): String? {
    if (url != null) return url
    if (source?.startsWith("detekt-rules-") != true || ruleSetId == KTLINT_RULE_SET_ID) return null
    return "https://detekt.dev/docs/rules/$ruleSetId#${ruleId.lowercase()}"
}

internal fun rulesPage(rules: List<RuleDescription>, theme: RulesTheme, linkQuery: String): String = buildString {
    append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
    append(css(theme))
    append("</style></head><body>")
    append("<header><input id=\"q\" type=\"search\" placeholder=\"Search ")
    append(rules.size).append(" rules\" autofocus></header><main>")
    if (rules.isEmpty()) {
        append("<p class=\"empty\">No detekt rules loaded.</p>")
    }
    for ((ruleSetId, ruleSet) in rules.groupBy { it.ruleSetId }.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
        append("<section><h1>").append(escape(ruleSetId)).append(" <span class=\"count\">")
        append(ruleSet.size).append("</span></h1>")
        ruleSet.firstNotNullOfOrNull { it.source }?.let { append("<p class=\"src\">").append(escape(it)).append("</p>") }
        for (rule in ruleSet) {
            append("<article data-search=\"").append(escape(rule.searchText())).append("\">")
            append("<h2>").append(escape(rule.ruleId)).append("</h2>")
            append("<p>").append(escape(rule.description.ifBlank { "No description." })).append("</p>")
            append("<div class=\"tags\">")
            if (rule.requiresTypeResolution) {
                append("<span class=\"tag\">Requires type resolution</span>")
            }
            rule.documentationUrl()?.let {
                append("<a href=\"#\" data-url=\"").append(escape(it)).append("\">Documentation</a>")
            }
            append("</div></article>")
        }
        append("</section>")
    }
    append("<p class=\"empty\" id=\"none\" hidden>No matching rules.</p></main><script>")
    append(script(linkQuery))
    append("</script></body></html>")
}

private fun RuleDescription.searchText(): String =
    listOfNotNull(ruleId, ruleSetId, description, source).joinToString(" ").lowercase()

private fun css(theme: RulesTheme): String = """
    :root { color-scheme: ${if (theme.background.luminance() < 0.5) "dark" else "light"}; }
    * { box-sizing: border-box; }
    body { margin: 0; background: ${theme.background.css()}; color: ${theme.foreground.css()};
        font-family: "${theme.fontFamily}", system-ui, sans-serif; font-size: ${theme.fontSize + 2}px; line-height: 1.6; }
    header { position: sticky; top: 0; padding: 12px 16px; background: ${theme.background.css()};
        border-bottom: 1px solid ${theme.border.css()}; z-index: 1; }
    input { width: 100%; padding: 8px 12px; border-radius: 6px; border: 1px solid ${theme.border.css()};
        background: ${theme.surface.css()}; color: inherit; font: inherit; outline: none; }
    input:focus { border-color: ${theme.accent.css()}; }
    main { padding: 0 16px 24px; }
    section { padding-top: 8px; }
    h1 { font-size: 1.7em; font-weight: 700; margin: 20px 0 0; }
    h1 .count { font-size: 0.5em; font-weight: 600; color: ${theme.muted.css()}; vertical-align: middle; }
    .src { margin: 0 0 4px; color: ${theme.muted.css()}; font-size: 0.85em; }
    article { padding: 14px 0; border-bottom: 1px solid ${theme.border.css()}; }
    h2 { font-size: 1.25em; font-weight: 700; margin: 0 0 4px; }
    article p { margin: 0 0 8px; }
    .tags { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; font-size: 0.9em; }
    .tag { padding: 1px 8px; border-radius: 4px; background: ${theme.surface.css()}; border: 1px solid ${theme.border.css()}; }
    a { color: ${theme.accent.css()}; text-decoration: none; }
    a:hover { text-decoration: underline; }
    .empty { color: ${theme.muted.css()}; padding-top: 24px; }
""".trimIndent()

private fun script(linkQuery: String): String = """
    const q = document.getElementById('q');
    const none = document.getElementById('none');
    q.addEventListener('input', () => {
        const term = q.value.trim().toLowerCase();
        let shown = 0;
        document.querySelectorAll('section').forEach(section => {
            let visible = 0;
            section.querySelectorAll('article').forEach(article => {
                const hit = !term || article.dataset.search.includes(term);
                article.hidden = !hit;
                if (hit) visible++;
            });
            section.hidden = visible === 0;
            shown += visible;
        });
        none.hidden = shown !== 0;
    });
    document.addEventListener('click', event => {
        const link = event.target.closest('a[data-url]');
        if (!link) return;
        event.preventDefault();
        const url = link.dataset.url;
        $linkQuery
    });
""".trimIndent()

private fun Color.css(): String = "rgb($red, $green, $blue)"

private fun Color.luminance(): Double = (0.299 * red + 0.587 * green + 0.114 * blue) / 255

private fun escape(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}
