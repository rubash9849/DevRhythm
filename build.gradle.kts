plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.3"
    kotlin("jvm") version "1.9.22"
}

group = "com.devrhythm"
version = "1.0.3"

repositories {
    mavenCentral()
}

intellij {
    version.set("2024.1")
    type.set("PC") // PyCharm Community Edition
    plugins.set(listOf())
    instrumentCode.set(false)

    // Sandbox directory
    sandboxDir.set("D:/devrhythm-sandbox")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

// Fix Kotlin stdlib conflict warning
configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}

tasks.patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("253.*")
}

tasks.buildPlugin {
    archiveFileName.set("DevRhythm-1.0.3.zip")
}

tasks.runIde {
    autoReloadPlugins.set(true)
}

tasks.runPluginVerifier {
    ideVersions.set(
        providers.gradleProperty("pluginVerifierIdeVersions").map { it.split(",") }
    )
}