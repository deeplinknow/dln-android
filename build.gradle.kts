import java.util.Base64
import java.io.FileInputStream

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

// Maven Central publishing configuration
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.deeplinknow"
                artifactId = "dln-android"
                version = "1.0.0"
                
                from(components["release"])
                
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
            // Local Maven repository for creating the bundle
            maven {
                name = "LocalMaven"
                url = uri("${buildDir}/local-maven-repo")
            }
        }
    }
}

// Signing configuration
signing {
    val signingKeyId = project.findProperty("signing.keyId") as String?
    val signingKey = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String?
    
    if (signingKeyId != null && signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications)
    } else {
        logger.warn("Signing properties not found. Artifacts will not be signed.")
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
        println("You can now upload this bundle to Central Portal using the curl command:")
        println("curl --request POST \\")
        println("  --header 'Authorization: Bearer <your-base64-encoded-token>' \\")
        println("  --form bundle=@${buildDir}/central-portal/central-portal-bundle.zip \\")
        println("  https://central.sonatype.com/api/v1/publisher/upload")
    }
}