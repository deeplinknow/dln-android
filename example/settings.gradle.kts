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
    
    // Ensure consistent Android Gradle Plugin version across all projects
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) {
                useVersion("8.3.2")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
    
    // Enable sharing the version catalog from the root project
    versionCatalogs {
        // This intentionally left empty to avoid duplicate entries
        // The version catalog will be picked up from root project
    }
}

rootProject.name = "DLNExample"
include(":app")

// Build against the LOCAL SDK source in the parent directory (packages/dln-android): the
// included build replaces the com.deeplinknow:dln-android dependency in app/build.gradle.kts.
// SWITCH: set to false after dln-android 1.1.0 is published, to use the Maven artifact instead.
val useLocalSdk = true

if (useLocalSdk) {
    includeBuild("..") {
        dependencySubstitution {
            substitute(module("com.deeplinknow:dln-android")).using(project(":"))
        }
    }
}

