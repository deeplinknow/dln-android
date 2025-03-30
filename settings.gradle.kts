rootProject.name = "dln-android"

pluginManagement {
    repositories {
        google()
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            // Core Android libraries
            library("androidx.core.ktx", "androidx.core:core-ktx:1.12.0")
            library("androidx.appcompat", "androidx.appcompat:appcompat:1.6.1")
            library("material", "com.google.android.material:material:1.11.0")
            library("androidx.constraintlayout", "androidx.constraintlayout:constraintlayout:2.1.4")
            
            // Lifecycle and UI components
            library("androidx.lifecycle.runtime", "androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
            library("androidx.activity", "androidx.activity:activity-ktx:1.8.2")
            
            // Testing
            library("junit", "junit:junit:4.13.2")
            library("androidx.junit", "androidx.test.ext:junit:1.1.5")
            library("androidx.espresso.core", "androidx.test.espresso:espresso-core:3.5.1")
            
            // Network and data handling
            library("kotlinx.coroutines.android.v173", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
            library("okhttp.android", "com.squareup.okhttp3:okhttp:4.12.0")
            library("gson.v2101", "com.google.code.gson:gson:2.10.1")
            
            // Additional testing dependencies
            library("mockk", "io.mockk:mockk:1.13.8")
            library("robolectric", "org.robolectric:robolectric:4.11.1")
            library("kotlinx.coroutines.test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            library("androidx.test.core", "androidx.test:core:1.5.0")
            library("androidx.test.rules", "androidx.test:rules:1.5.0")
            
            // Gradle plugins
            plugin("androidLibrary", "com.android.library").version("8.3.2")
            plugin("jetbrainsKotlinAndroid", "org.jetbrains.kotlin.android").version("1.9.0")
            plugin("androidApplication", "com.android.application").version("8.3.2")
        }
    }
} 