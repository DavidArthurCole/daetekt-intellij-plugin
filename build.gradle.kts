import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

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

fun Configuration.runtimeJarAttributes() {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

val detektRuntimeScope = configurations.dependencyScope("detektRuntimeScope")
val detektRuntime = configurations.resolvable("detektRuntime") {
    extendsFrom(detektRuntimeScope.get())
    runtimeJarAttributes()
}

val testRulesScope = configurations.dependencyScope("testRulesScope")
val testRules = configurations.resolvable("testRules") {
    extendsFrom(testRulesScope.get())
    runtimeJarAttributes()
}

dependencies {
    implementation(project(":engine-api"))
    add(detektRuntimeScope.name, project(":engine"))
    add(testRulesScope.name, project(":engine-test-rules"))

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
}

val syncDetektRuntime = tasks.register<Sync>("syncDetektRuntime") {
    from(detektRuntime)
    into(layout.buildDirectory.dir("detekt-runtime"))
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(syncDetektRuntime) {
        into(pluginName.map { "$it/detekt" })
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(syncDetektRuntime)
    inputs.files(testRules)
    val runtimeDirectory = syncDetektRuntime.map { it.destinationDir.absolutePath }
    val rulesJar = testRules.map { configuration ->
        configuration.files.single { it.name.startsWith("engine-test-rules") }.absolutePath
    }
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dintellijekt.detekt.runtime=${runtimeDirectory.get()}",
            "-Dintellijekt.test.rulesJar=${rulesJar.get()}",
        )
    }
    testLogging {
        showStandardStreams = true
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
        name.set("intellijekt")

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
        .file("distributions/Detekt IntelliJ Plugin-${project.version}.zip")
    releaseAssets.setFrom(distribution)
}

tasks.githubRelease.configure {
    dependsOn(tasks.buildPlugin)
}
