plugins {
    alias(libs.plugins.dokka)
}

dependencies {
    dokka(project(":cardio-protocol"))
    dokka(project(":cardio-core"))
    dokka(project(":cardio-serialization"))
}

dokka {
    moduleName.set("Cardio")

    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}
