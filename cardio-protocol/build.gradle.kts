plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.ktor.network)
}

kotlin {
    jvmToolchain(21)
}