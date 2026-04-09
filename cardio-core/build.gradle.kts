plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)

    `java-library`
}

dependencies {
    testImplementation(kotlin("test"))

    api(libs.kotlinx.coroutines.core)
    api(project(":cardio-protocol"))

    implementation(libs.slf4j.api)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dokka {
    moduleName.set("cardio-core")
    dokkaSourceSets.main {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl.set(uri("https://github.com/Blad3Mak3r/cardio/tree/main/cardio-core/src/main/kotlin"))
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    coordinates("io.github.blad3mak3r.cardio", "cardio-core", "$version")

    pom {
        name.set("cardio-core")
        description.set("Coroutine-native PostgreSQL library for Kotlin")
        url.set("https://github.com/Blad3Mak3r/cardio")
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Blad3Mak3r/cardio/issues")
        }
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://github.com/Blad3Mak3r/cardio/LICENSE.txt")
                distribution.set("repo")
            }
        }
        scm {
            url.set("https://github.com/Blad3Mak3r/cardio")
            connection.set("https://github.com/Blad3Mak3r/cardio.git")
            developerConnection.set("scm:git:ssh://git@github.com:Blad3Mak3r/cardio.git")
        }
        developers {
            developer {
                name.set("Juan Luis Caro")
                url.set("https://github.com/Blad3Mak3r")
            }
        }
    }

    publishToMavenCentral(automaticRelease = true)

    signAllPublications()
}