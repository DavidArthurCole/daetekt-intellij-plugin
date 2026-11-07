package io.gitlab.arturbosch.detekt.idea.testrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtFile

class TestRuleSetProvider : RuleSetProvider {

    override val ruleSetId: RuleSetId = RuleSetId("daetekt-test")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf({ config: Config -> FileMarker(config) }, { config: Config -> TypeResolvedFileMarker(config) }),
    )
}

class FileMarker(config: Config) : Rule(config, "Reports every file it visits.") {

    override fun visitKtFile(file: KtFile) {
        report(Finding(Entity.atPackageOrFirstDecl(file), "Visited ${file.name}."))
    }
}

class TypeResolvedFileMarker(config: Config) :
    Rule(config, "Reports every file it visits when an analysis session exists."),
    RequiresAnalysisApi {

    override fun visitKtFile(file: KtFile) {
        report(Finding(Entity.atPackageOrFirstDecl(file), "Resolved ${file.name}."))
    }
}
