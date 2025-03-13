import java.util.Base64
import java.io.FileInputStream
import java.io.File

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.deeplinknow"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        testOptions {
            unitTests.isReturnDefaultValues = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.jvmArgs("-noverify")
                it.systemProperty("robolectric.logging", "stdout")
                it.systemProperty("javax.net.ssl.trustStoreType", "JKS")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android.v173)
    implementation(libs.okhttp.android)
    implementation(libs.gson.v2101)
    
    // Unit testing
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.12.5")
    testImplementation("io.mockk:mockk-android:1.12.5")
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.robolectric:shadows-framework:4.11.1")
    
    // Instrumentation testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
}

// Create the publication before configuring signing
val publishingConfig = extensions.getByType<PublishingExtension>().apply {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.deeplinknow"
            artifactId = "dln-android"
            version = "1.0.11"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("DeepLinkNow Android SDK")
                description.set("Android SDK for DeepLinkNow service")
                url.set("https://github.com/jvgeee/dln-android")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("DeepLinkNow")
                        name.set("DeepLinkNow")
                        email.set("jvg@deeplinknow.com")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/jvgeee/dln-android.git")
                    developerConnection.set("scm:git:ssh://https://github.com/jvgeee/dln-android.git")
                    url.set("https://github.com/jvgeee/dln-android")
                }
            }
        }
    }

    repositories {
        maven {
            name = "LocalMaven"
            url = uri("${buildDir}/local-maven-repo")
        }
    }
}

// Configure signing
signing {
    val keyId = findProperty("signing.keyId") as String?
    val key = findProperty("signing.key") as String?
    val password = findProperty("signing.password") as String?

    useInMemoryPgpKeys(keyId, key, password)
    sign(publishing.publications["release"])
}

// Task to verify signing configuration
tasks.register("verifySigningConfig") {
    doLast {
        val signingKeyId: String? = project.findProperty("signing.keyId") as String?
        val signingKey: String? = project.findProperty("signing.key") as String?
        val signingPassword: String? = project.findProperty("signing.password") as String?

        println("Signing Configuration Status:")
        println("  signing.keyId: ${if (signingKeyId != null) "✓" else "✗"}")
        println("  signing.key: ${if (signingKey != null) "✓" else "✗"}")
        println("  signing.password: ${if (signingPassword != null) "✓" else "✗"}")

        if (signingKeyId != null && signingKey != null && signingPassword != null) {
            println("\nAll signing properties are present!")
        } else {
            println("\nMissing signing properties!")
            println("Make sure your gradle.properties contains:")
            println("signing.keyId=<your-key-id>")
            println("signing.key=<your-ascii-armored-key>")
            println("signing.password=<your-key-password>")
        }
    }
}

// Task to create a bundle for Central Portal
tasks.register<Zip>("createCentralPortalBundle") {
    dependsOn("publishReleasePublicationToLocalMavenRepository")

    from("${buildDir}/local-maven-repo")
    destinationDirectory.set(file("${buildDir}/central-portal"))
    archiveFileName.set("central-portal-bundle.zip")

    doLast {
        println("Central Portal bundle created at: ${buildDir}/central-portal/central-portal-bundle.zip")
    }
}

// Create sources JAR
tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.srcDirs)
}

// Create Javadoc JAR
tasks.register<Jar>("androidJavadocJar") {
    archiveClassifier.set("javadoc")
    from("$buildDir/docs/javadoc")
}