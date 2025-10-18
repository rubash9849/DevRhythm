plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.3"
    kotlin("jvm") version "1.9.22"
}

group = "com.devrhythm"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

intellij {
    version = "2023.3.7"
    type = "PC" // PyCharm Community
    plugins = listOf("python-ce")

    // ✅ Fix: sandboxDir expects a string path
    sandboxDir.set("sandbox")
}

tasks {
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("252.*")
    }
    buildSearchableOptions {
        enabled = false
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
