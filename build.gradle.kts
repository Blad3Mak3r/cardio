plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val gitTag: String? by lazy {
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0")
        workingDir(rootProject.projectDir)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.orNull
}

val gitHash: String? by lazy {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir(rootProject.projectDir)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.orNull
}

rootProject.version = gitTag ?: gitHash ?: "dev"

val kotlinJvmPluginId = libs.plugins.kotlin.jvm.get().pluginId

subprojects {
    this.version = rootProject.version
    apply(plugin = kotlinJvmPluginId)

    repositories {
        mavenCentral()
    }
}