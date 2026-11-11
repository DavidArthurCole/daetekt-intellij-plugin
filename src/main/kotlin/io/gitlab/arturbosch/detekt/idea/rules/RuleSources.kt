package io.gitlab.arturbosch.detekt.idea.rules

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.gitlab.arturbosch.detekt.idea.engine.api.RuleExample
import java.util.concurrent.Callable

internal class SourceDoc(val rationale: String?, val examples: List<RuleExample>)

internal class RuleSource(val file: VirtualFile, val line: Int, val column: Int, val doc: SourceDoc?) {

    val display: String
        get() = "${file.name}:${line + 1}"

    fun navigate(project: Project) {
        if (file.isValid) OpenFileDescriptor(project, file, line, column).navigate(true)
    }
}

internal fun locateRuleSources(project: Project, classNames: Collection<String>): Map<String, RuleSource> {
    if (classNames.isEmpty()) return emptyMap()
    val targets = classNames.toSet()
    return ReadAction.nonBlocking(Callable { locate(project, targets) })
        .inSmartMode(project)
        .expireWith(project)
        .executeSynchronously()
}

private fun locate(project: Project, classNames: Set<String>): Map<String, RuleSource> {
    val scopes = listOf(GlobalSearchScope.projectScope(project), GlobalSearchScope.allScope(project))
    return classNames.map(::ClassTarget).mapNotNull { target ->
        scopes.asSequence()
            .flatMap { scope -> target.fileNames.asSequence().flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() } }
            .firstNotNullOfOrNull { file -> loadText(file)?.let { target.find(file, it) } }
            ?.let { target.className to it }
    }.toMap()
}

private fun loadText(file: VirtualFile): String? = runCatching { VfsUtilCore.loadText(file) }.getOrNull()

private class ClassTarget(val className: String) {

    private val topLevel = className.substringBefore('$')
    private val packageName = topLevel.substringBeforeLast('.', "")
    private val simpleName = className.substringAfterLast('.').substringAfterLast('$')
    private val declaration = Regex("""\b(?:class|object|interface)\s+${Regex.escape(simpleName)}\b""")

    val fileNames = topLevel.substringAfterLast('.').let { listOf("$it.kt", "$it.java") }

    fun find(file: VirtualFile, text: String): RuleSource? {
        if (!text.contains(simpleName) || packageOf(text) != packageName) return null
        val match = declaration.find(text) ?: return null
        val nameOffset = match.range.last + 1 - simpleName.length
        val lineStart = text.lastIndexOf('\n', nameOffset - 1) + 1
        val line = text.subSequence(0, nameOffset).count { it == '\n' }
        return RuleSource(file, line, nameOffset - lineStart, kdocBefore(text, match.range.first)?.let(::parseDoc))
    }
}

private val PACKAGE = Regex("""^\s*package\s+([\w.`]+)""", RegexOption.MULTILINE)
private val ANNOTATION = Regex("""@[\w.:]+(?:\([^)]*\))?""")
private val MODIFIERS = Regex("""\b(?:public|internal|private|protected|open|abstract|sealed|data|final|inner|annotation|enum|value|expect|actual)\b""")
private val NONCOMPLIANT = Regex("""<noncompliant>(.*?)</noncompliant>""", RegexOption.DOT_MATCHES_ALL)
private val COMPLIANT = Regex("""<compliant>(.*?)</compliant>""", RegexOption.DOT_MATCHES_ALL)
private val BLANK_RUNS = Regex("""\n{3,}""")

private fun packageOf(text: String): String = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "").orEmpty()

private fun kdocBefore(text: String, declarationStart: Int): String? {
    val end = text.lastIndexOf("*/", declarationStart)
    if (end < 0) return null
    val residue = text.substring(end + 2, declarationStart).replace(ANNOTATION, "").replace(MODIFIERS, "")
    if (residue.isNotBlank()) return null
    val start = text.lastIndexOf("/**", end)
    if (start < 0) return null
    return text.substring(start + 3, end).lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*")) trimmed.drop(1).removePrefix(" ") else trimmed
    }.trim()
}

private fun parseDoc(kdoc: String): SourceDoc {
    val bad = NONCOMPLIANT.findAll(kdoc).map { it.groupValues[1].trimIndent() }.toList()
    val good = COMPLIANT.findAll(kdoc).map { it.groupValues[1].trimIndent() }.toList()
    val prose = kdoc.replace(NONCOMPLIANT, "").replace(COMPLIANT, "")
        .lines()
        .filterNot { it.trimStart().startsWith("@") }
        .joinToString("\n")
        .replace(BLANK_RUNS, "\n\n")
        .trim()
    val examples = (0 until maxOf(bad.size, good.size)).map {
        RuleExample(null, bad.getOrNull(it)?.ifBlank { null }, good.getOrNull(it)?.ifBlank { null }, null)
    }
    return SourceDoc(prose.ifEmpty { null }, examples)
}
