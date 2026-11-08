package io.gitlab.arturbosch.detekt.idea.rules

import io.gitlab.arturbosch.detekt.idea.engine.api.KTLINT_RULE_SET_ID
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleDescription
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleExample
import java.awt.Color

internal class RulesTheme(
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val surface: Color,
    val border: Color,
    val codeBackground: Color,
    val fontFamily: String,
    val fontSize: Int,
    val codeFontFamily: String,
    val codeFontSize: Int,
)

internal class RuleEntry(val rule: RuleDescription, val source: RuleSource?)

internal fun RuleDescription.documentationUrl(): String? {
    if (url != null) return url
    if (source?.startsWith("detekt-rules-") != true || ruleSetId == KTLINT_RULE_SET_ID) return null
    return "https://detekt.dev/docs/rules/$ruleSetId#${ruleId.lowercase()}"
}

internal fun rulesPage(
    rules: List<RuleEntry>,
    theme: RulesTheme,
    bridge: String,
    message: String? = null,
    initial: String? = null,
): String = buildString {
    append("<!DOCTYPE html><html data-theme=\"").append(if (theme.dark) "dark" else "light").append("\"><head><meta charset=\"utf-8\"><style>")
    append(variables(theme))
    append(resource("rules.css"))
    append("</style></head><body><div id=\"app\"></div><script>")
    append("const RULES = ").append(json(rules.map { it.toJson() }).scriptSafe()).append(";\n")
    append("const MESSAGE = ").append(json(message).scriptSafe()).append(";\n")
    append("const INITIAL = (() => { try { return JSON.parse(").append(json(initial).scriptSafe()).append("); } catch (e) { return null; } })();\n")
    append("function bridge(message) {\n").append(bridge).append("\n}\n")
    append(resource("rules.js"))
    append("</script></body></html>")
}

private fun RuleEntry.toJson(): Map<String, Any?> {
    val doc = source?.doc
    val rationale = (rule.rationale ?: doc?.rationale)?.takeUnless { it.trim() == rule.description.trim() }
    return mapOf(
        "id" to rule.ruleId,
        "set" to rule.ruleSetId,
        "desc" to rule.description,
        "url" to rule.documentationUrl(),
        "custom" to rule.custom,
        "active" to rule.active,
        "typeRes" to rule.requiresTypeResolution,
        "autoCorrect" to rule.autoCorrect,
        "jar" to rule.source,
        "cls" to rule.className,
        "src" to source?.display,
        "rationale" to rationale,
        "examples" to rule.examples.ifEmpty { doc?.examples.orEmpty() }.map { it.toJson() },
        "options" to rule.options.map {
            mapOf("name" to it.name, "desc" to it.description, "def" to it.defaultValue, "value" to it.configuredValue)
        },
    )
}

private fun RuleExample.toJson(): Map<String, Any?> =
    mapOf("title" to title, "before" to before, "after" to after, "note" to note)

private fun variables(theme: RulesTheme): String = buildString {
    append(":root {")
    append("color-scheme: ").append(if (theme.dark) "dark" else "light").append(";")
    append("--bg: ").append(theme.background.css()).append(";")
    append("--fg: ").append(theme.foreground.css()).append(";")
    append("--muted: ").append(theme.muted.css()).append(";")
    append("--accent: ").append(theme.accent.css()).append(";")
    append("--surface: ").append(theme.surface.css()).append(";")
    append("--border: ").append(theme.border.css()).append(";")
    append("--code-bg: ").append(theme.codeBackground.css()).append(";")
    append("--font: \"").append(theme.fontFamily.cssString()).append("\", system-ui, sans-serif;")
    append("--font-size: ").append(theme.fontSize + 1).append("px;")
    append("--code-font: \"").append(theme.codeFontFamily.cssString()).append("\", ui-monospace, monospace;")
    append("--code-size: ").append(theme.codeFontSize).append("px;")
    append("}\n")
}

private fun resource(name: String): String =
    requireNotNull(RuleEntry::class.java.getResource("/rules/$name")) { "Missing /rules/$name" }.readText()

private fun Color.css(): String = "rgb($red, $green, $blue)"

private fun String.cssString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun String.scriptSafe(): String = replace("</", "<\\/")

internal fun json(value: Any?): String = buildString { appendJson(value) }

private fun StringBuilder.appendJson(value: Any?) {
    when (value) {
        null -> append("null")
        is Boolean, is Int, is Long -> append(value)
        is String -> appendJsonString(value)
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, (key, item) ->
                if (index > 0) append(',')
                appendJsonString(key.toString())
                append(':')
                appendJson(item)
            }
            append('}')
        }
        is Iterable<*> -> {
            append('[')
            value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendJson(item)
            }
            append(']')
        }
        else -> appendJsonString(value.toString())
    }
}

private fun StringBuilder.appendJsonString(text: String) {
    append('"')
    for (char in text) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' || char == ' ' || char == ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    append('"')
}
