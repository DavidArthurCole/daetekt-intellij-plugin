plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":engine-api"))

    implementation(libs.detekt.api)
    implementation(libs.detekt.tooling)

    runtimeOnly(libs.detekt.cli)
    runtimeOnly(libs.detekt.ktlintWrapper)
}

kotlin {
    jvmToolchain(21)
}
