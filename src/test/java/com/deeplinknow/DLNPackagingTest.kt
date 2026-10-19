package com.deeplinknow

import com.deeplinknow.DLNTestSupport.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Packaging and source-level guarantees (AND-01..04, AND-08, AND-10, SDK-61/62, SDK-70).
 * Plain JUnit: these read the module's build files and sources.
 */
class DLNPackagingTest {

    private val gradle by lazy { moduleFile("build.gradle.kts").readText() }
    private val source by lazy { moduleFile("src/main/java/com/deeplinknow/DeepLinkNow.kt").readText() }
    private val mainSources by lazy {
        moduleFile("src/main/java/com/deeplinknow").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    private fun version(): String {
        val m = Regex("""(?m)^version\s*=\s*"([^"]+)"""").find(gradle)
        assertNotNull("no version in build.gradle.kts", m)
        return m!!.groupValues[1]
    }

    @Test
    fun `AND-01 FP-21 SDK_VERSION sent on init matches the published version`() {
        val version = Regex("""^version = "([^"]+)"""", RegexOption.MULTILINE).find(gradle)!!.groupValues[1]
        org.junit.Assert.assertEquals(version, DLN.SDK_VERSION)
    }

    @Test
    fun `AND-01 artifact coordinates, minSdk 24 and one consistent version in build, README and DOCS`() {
        assertTrue(gradle.contains("groupId = \"com.deeplinknow\""))
        assertTrue(gradle.contains("artifactId = \"dln-android\""))
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1].toInt()
        assertTrue(minSdk <= 24)
        val v = version()
        // both version declarations in build.gradle.kts agree
        val declared = Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""").findAll(gradle).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(v), declared)
        for (doc in listOf("README.md", "DOCS.md")) {
            val text = moduleFile(doc).readText()
            assertTrue("$doc does not reference dln-android:$v", text.contains("com.deeplinknow:dln-android:$v"))
            val others = Regex("""com\.deeplinknow:dln-android:(\d+\.\d+\.\d+)""").findAll(text).map { it.groupValues[1] }.toSet()
            assertEquals("$doc references other versions", setOf(v), others)
        }
    }

    @Test
    fun `AND-01 the version was bumped as a minor (1_2_x, in step with iOS and React Native) for the additive API`() {
        assertTrue(version(), version().startsWith("1.2."))
    }

    @Test
    fun `AND-02 the library manifest declares INTERNET`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `AND-03 no unused UI dependencies (appcompat, material)`() {
        val deps = gradle.substringAfter("dependencies {").substringBefore("\n}")
        val implementation = deps.lines().filter { it.trim().startsWith("implementation") || it.trim().startsWith("api") }
        assertFalse(implementation.toString(), implementation.any { it.contains("appcompat") || it.contains("material") })
    }

    @Test
    fun `AND-04 consumer-rules keep the Gson model classes and their fields`() {
        assertTrue(gradle.contains("consumerProguardFiles(\"consumer-rules.pro\")"))
        val rules = moduleFile("consumer-rules.pro").readText()
        for (model in listOf("DeeplinkMatch", "Match", "MatchResponse", "MatchDetails", "InitResponse", "ReferrerData", "Fingerprint", "ReferrerLookupResponse")) {
            assertTrue("consumer-rules.pro does not keep $model", Regex("""-keep[^\n]*com\.deeplinknow\.$model\b""").containsMatchIn(rules) ||
                rules.contains("-keep class com.deeplinknow.** {"))
        }
        assertTrue(rules.contains("SerializedName"))
    }

    @Test
    fun `AND-08 GlobalScope is never used`() {
        assertFalse(mainSources.contains("GlobalScope"))
        assertTrue(mainSources.contains("SupervisorJob"))
        assertTrue(mainSources.contains("CoroutineExceptionHandler"))
    }

    @Test
    fun `AND-10 SDK-70 no ungated DLN_Screen logging and one log tag`() {
        assertFalse(source.contains("DLN_Screen"))
        val tags = Regex("""Log\.[dwie]\("([^"]+)"""").findAll(mainSources).map { it.groupValues[1] }.toSet()
        assertTrue(tags.toString(), tags.isEmpty() || tags == setOf("DLN"))
    }

    @Test
    fun `SDK-61 no advertising ID, no play-services-ads-identifier`() {
        assertFalse(gradle.contains("play-services-ads"))
        assertFalse(mainSources.contains("AdvertisingIdClient"))
    }

    @Test
    fun `SDK-62 no location, telephony, installed-apps or hardware identifiers`() {
        for (forbidden in listOf("TelephonyManager", "LocationManager", "getInstalledPackages", "getInstalledApplications", "Build.SERIAL", "getSerial", "WifiManager", "getImei", "getDeviceId(")) {
            assertFalse(forbidden, mainSources.contains(forbidden))
        }
    }
}
