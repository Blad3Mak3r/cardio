plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)

    `java-library`
}

dependencies {
    implementation(project(":cardio-core"))
}

kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    coordinates("io.github.blad3mak3r.cardio", "cardio-serialization", "$version")

    pom {
        name.set("cardio-serialization")
        description.set("kotlinx.serialization bridge for Cardio, a coroutine-native PostgreSQL library for Kotlin")
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