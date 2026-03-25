plugins {
    alias(libs.plugins.kotlin.jvm)

    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    api(libs.coroutines.core)
    api(project(":cardio-protocol"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnitPlatform()
}