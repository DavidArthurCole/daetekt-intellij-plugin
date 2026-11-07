package io.gitlab.arturbosch.detekt.idea.intention

import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate

internal class TemplateEdit(val start: Int, val end: Int, val original: String, val replacement: String)

internal fun FixTemplate.editWithin(text: CharSequence, start: Int, end: Int): TemplateEdit? {
    if (start < 0 || end > text.length || start >= end) return null
    val index = text.subSequence(start, end).toString().indexOf(find, ignoreCase = ignoreCase)
    if (index < 0) return null
    val original = text.subSequence(start + index, start + index + find.length).toString()
    return TemplateEdit(start + index, start + index + find.length, original, matchCase(original, replace))
}

internal fun matchCase(original: String, replacement: String): String = when {
    original.isEmpty() || replacement.isEmpty() -> replacement
    original.length > 1 && original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
    original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
    original.first().isLowerCase() -> replacement.replaceFirstChar { it.lowercaseChar() }
    else -> replacement
}
