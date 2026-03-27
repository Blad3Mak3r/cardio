plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":cardio-core"))
}