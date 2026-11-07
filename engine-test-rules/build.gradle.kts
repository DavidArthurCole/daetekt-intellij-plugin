plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.detekt.api)
}

kotlin {
    jvmToolchain(21)
}
