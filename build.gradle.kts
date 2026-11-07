import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.versions)
    alias(libs.plugins.github.release)
}

project.group = "io.gitlab.arturbosch.detekt"
project.version = libs.versions.detektIJ.get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val engine = sourceSets.create("engine")
val testRules = sourceSets.create("testRules")

dependencies {
    engine.compileOnlyConfigurationName(sourceSets.main.get().output)
    engine.implementationConfigurationName(libs.detekt.api)
    engine.implementationConfigurationName(libs.detekt.tooling)
    engine.runtimeOnlyConfigurationName(libs.detekt.cli)
    engine.runtimeOnlyConfigurationName(libs.detekt.ktlintWrapper)

    testRules.compileOnlyConfigurationName(libs.detekt.api)

    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform)
    testRuntimeOnly(libs.junit4)

    intellijPlatform {
        intellijIdeaCommunity("2024.2")

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.intelliLang")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)

        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

val engineJar = tasks.register<Jar>("engineJar") {
    archiveBaseName = "daetekt-engine"
    from(engine.output)
}

val testRulesJar = tasks.register<Jar>("testRulesJar") {
    archiveBaseName = "daetekt-test-rules"
    from(testRules.output)
}

val syncDetektRuntime = tasks.register<Sync>("syncDetektRuntime") {
    from(engineJar)
    from(configurations.named(engine.runtimeClasspathConfigurationName))
    into(layout.buildDirectory.dir("detekt-runtime"))
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(syncDetektRuntime) {
        into(pluginName.map { "$it/detekt" })
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.files(syncDetektRuntime, testRulesJar)
    val runtimeDirectory = syncDetektRuntime.map { it.destinationDir.absolutePath }
    val rulesJar = testRulesJar.flatMap { it.archiveFile }.map { it.asFile.absolutePath }
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Ddaetekt.detekt.runtime=${runtimeDirectory.get()}",
            "-Ddaetekt.test.rulesJar=${rulesJar.get()}",
        )
    }
    testLogging {
        showExceptions = true
        showCauses = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.publishPlugin {
    // This property can be configured via environment variable ORG_GRADLE_PROJECT_intellijPublishToken
    // See: https://docs.gradle.org/current/userguide/build_environment.html#sec:project_properties
    token.set((findProperty("intellijPublishToken") as? String).orEmpty())
    // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
    // "-beta" is used for pre-releases and https://plugins.jetbrains.com/plugins/beta/list as plugin repository.
    channels.set(listOf(project.version.toString().split('-').getOrElse(1) { "default" }.split('.').first()))
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name.set("daetekt")

        ideaVersion {
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides { recommended() }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }
}

githubRelease {
    token((project.findProperty("github.token") as? String).orEmpty())
    owner.set("detekt")
    repo.set("detekt-intellij-plugin")
    targetCommitish.set("main")
    overwrite.set(true)
    dryRun.set(false)
    body.set(
        provider {
            var changelog = project.file("changelog.md").readText()
            val sectionStart = "#### ${project.version}"
            changelog = changelog.substring(changelog.indexOf(sectionStart) + sectionStart.length)
            changelog = changelog.substring(0, changelog.indexOf("#### 1"))
            changelog.trim()
        }
    )
    val distribution = project.layout.buildDirectory
        .file("distributions/daetekt-${project.version}.zip")
    releaseAssets.setFrom(distribution)
}

tasks.githubRelease.configure {
    dependsOn(tasks.buildPlugin)
}
