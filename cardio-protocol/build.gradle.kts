plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.vertx.kotlin.coroutines)
    api(libs.ktor.network)
}