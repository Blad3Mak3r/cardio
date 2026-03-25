plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.coroutines.core)
    api(project(":cardio-protocol"))
}