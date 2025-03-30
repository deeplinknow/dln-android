// This file just serves as a container for the app module
// All actual build configuration is in app/build.gradle.kts

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
}

// Nothing else needed here as this is just a container for the app module 