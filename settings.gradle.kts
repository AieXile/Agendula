pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// No Gradle Java-toolchain auto-download resolver plugin: it can fetch a JDK at
// build time, which an offline / reproducible F-Droid build scanner rejects.
// Modules set jvmTarget directly, so no toolchain resolver is needed.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Agendula"
include(":app")
include(":dav")
include(":caldav")
includeBuild("floret-kit")
