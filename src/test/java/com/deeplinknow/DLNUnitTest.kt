package com.deeplinknow

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Pure helpers: query parsing (SDK-35), referrer parsing (AND-15), host rule (SDK-31), decoders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNUnitTest {

    private val gson = Gson()

    // ---- SDK-35 URLSearchParams-equivalent parser ----

    @Test
    fun `SDK-35 parseQuery matches URLSearchParams on the FLOW-53 examples`() {
        assertEquals(emptyMap<String, QueryValue>(), DLNLinks.parseQuery(""))
        assertEquals(emptyMap<String, QueryValue>(), DLNLinks.parseQuery(null))
        assertEquals(mapOf("a" to QueryValue.Single("1")), DLNLinks.parseQuery("a=1"))
        assertEquals(mapOf("a" to QueryValue.Single("1")), DLNLinks.parseQuery("?a=1"))
        assertEquals(mapOf("k" to QueryValue.Flag), DLNLinks.parseQuery("k"))
        assertEquals(mapOf("k" to QueryValue.Flag), DLNLinks.parseQuery("k="))
        assertEquals(mapOf("k" to QueryValue.Multiple(listOf("1", "2"))), DLNLinks.parseQuery("k=1&k=2"))
        assertEquals(mapOf("k" to QueryValue.Multiple(listOf("", ""))), DLNLinks.parseQuery("k&k="))
        assertEquals(mapOf("a" to QueryValue.Single("b=c")), DLNLinks.parseQuery("a=b=c"))
        assertEquals(mapOf("a b" to QueryValue.Single("c d")), DLNLinks.parseQuery("a+b=c%20d"))
    }

    @Test
    fun `SDK-34 pathFromFullPath stops at the first question mark without decoding`() {
        assertEquals("/a%20b", DLNLinks.pathFromFullPath("/a%20b?x=1?y"))
        assertEquals("/", DLNLinks.pathFromFullPath(""))
        assertEquals("/p", DLNLinks.pathFromFullPath("/p#frag"))
    }

    // ---- AND-15 referrer parsing ----

    @Test
    fun `AND-15 parseReferrer takes exact keys, decodes, validates UUIDs and prefers fp_id`() {
        val fp = DLNTestSupport.FP_ID
        val dl = DLNTestSupport.DEEPLINK_ID
        assertEquals(fp to null, DLNLinks.parseReferrer("fp_id=$fp"))
        assertEquals(fp to null, DLNLinks.parseReferrer("fp_id%3D$fp"))
        assertEquals(fp to dl, DLNLinks.parseReferrer("deeplink_id=$dl&fp_id=$fp"))
        assertEquals(null to null, DLNLinks.parseReferrer("xfp_id=$fp&my_deeplink_id=$dl"))
        assertEquals(null to null, DLNLinks.parseReferrer("fp_id=not-a-uuid"))
        assertEquals(null to null, DLNLinks.parseReferrer("utm_source=google-play&utm_medium=organic"))
        assertEquals(null to null, DLNLinks.parseReferrer("%%%"))
        assertEquals(fp.uppercase() to null, DLNLinks.parseReferrer("fp_id=${fp.uppercase()}"))
    }

    // ---- SDK-31 host rule ----

    @Test
    fun `SDK-31 normalizeHost lowercases and drops a trailing dot and port`() {
        assertEquals("myapp.deeplinknow.com", DLNLinks.normalizeHost("MyApp.DeepLinkNow.com.:443"))
        assertEquals("go.myapp.com", DLNLinks.normalizeHost("GO.myapp.com"))
    }

    @Test
    fun `SDK-31 SDK-37 isAllowedHost exact after init, provisional before`() {
        val domains = setOf("myapp.deeplinknow.com", "go.myapp.com")
        assertTrue(DLNLinks.isAllowedHost("myapp.deeplinknow.com", domains))
        assertFalse(DLNLinks.isAllowedHost("other.deeplinknow.com", domains))
        assertTrue(DLNLinks.isAllowedHost("other.deeplinknow.com", null))
        assertFalse(DLNLinks.isAllowedHost("go.myapp.com", null))
        assertFalse(DLNLinks.isAllowedHost("deeplinknow.com", setOf("deeplinknow.com")))
        assertFalse(DLNLinks.isAllowedHost("x.deeplink.now", setOf("x.deeplink.now")))
    }

    // ---- Models (Gson back-compat for apps that serialize them) ----

    @Test
    fun `fingerprint serialization round trip with null ad and vendor ids`() {
        val currentTime = Instant.now().toString()
        val fingerprint = Fingerprint(
            userAgent = "test-agent",
            platform = "android",
            osVersion = "11",
            deviceModel = "test-model",
            language = "en-US",
            timezone = "UTC",
            installedAt = currentTime,
            lastOpenedAt = currentTime,
            deviceId = "test-id",
            advertisingId = null,
            vendorId = null,
            hardwareFingerprint = "test-fingerprint",
            screenWidth = 390,
            screenHeight = 844,
            pixelRatio = 3.0f
        )
        val deserialized = gson.fromJson(gson.toJson(fingerprint), Fingerprint::class.java)
        assertEquals(fingerprint.userAgent, deserialized.userAgent)
        assertEquals(fingerprint.screenWidth, deserialized.screenWidth)
        assertNull(deserialized.advertisingId)
    }

    @Test
    fun `SDK-20 decodeMatchResponse handles numbers, missing optional fields and unknown fields`() {
        val json = """
            {
                "matches": [{
                    "confidence_score": 85,
                    "match_quality": "HIGH",
                    "unknown_field": {"x": 1},
                    "match_details": {
                        "ip_match": { "matched": true, "score": 40 },
                        "device_match": { "matched": true, "score": 22.5,
                            "components": { "platform": true, "os_version": true, "device_model": false, "hardware_fingerprint": true } },
                        "time_proximity": { "score": 18.1, "time_difference_minutes": 5 },
                        "locale_match": { "matched": true, "score": 5, "components": { "language": true, "timezone": false } }
                    },
                    "deeplink": {
                        "id": "test-id",
                        "target_url": "https://example.com",
                        "metadata": {},
                        "campaign_id": "test-campaign",
                        "matched_at": "${Instant.now()}",
                        "expires_at": "${Instant.now().plusSeconds(3600)}",
                        "query_params": {},
                        "full_path": "/"
                    }
                }],
                "ttl_seconds": 3600
            }
        """.trimIndent()

        val response = DLNJson.decodeMatchResponse(JsonParser.parseString(json))
        assertEquals(1, response.matches.size)
        assertEquals(3600, response.ttlSeconds)
        val match = response.matches[0]
        assertEquals(85.0, match.confidenceScore, 0.001)
        assertEquals("HIGH", match.matchQuality)
        assertFalse(match.matchDetails.deviceMatch.components.deviceModel)
        assertEquals(5, match.matchDetails.timeProximity.timeDifferenceMinutes)
        assertFalse(match.matchDetails.localeMatch.components.timezone)
        assertEquals("test-campaign", match.deeplink!!.campaignId)
        assertEquals("/", match.deeplink!!.fullPath)
    }

    @Test
    fun `SDK-03 decodeInitResponse reads alias and certificates, tolerates nulls`() {
        val json = """
            {
                "app": {
                    "id": "test-app", "name": "Test App", "alias": "test", "timezone": "UTC",
                    "android_package_name": "com.test.app", "android_sha256_cert": "test-cert",
                    "android_sha256_certificates": ["test-cert"],
                    "ios_bundle_id": null, "ios_app_store_id": null, "ios_app_prefix": null,
                    "custom_domains": [ { "domain": "test.com", "verified": true }, { "domain": "unverified.com", "verified": false } ]
                },
                "account": { "status": "active", "credits_remaining": 1000, "rate_limits": { "matches_per_second": 10, "matches_per_day": 1000 } }
            }
        """.trimIndent()
        val response = DLNJson.decodeInitResponse(JsonParser.parseString(json))
        assertEquals("test-app", response.app.id)
        assertEquals("test", response.app.alias)
        assertEquals(listOf("test-cert"), response.app.androidSha256Certificates)
        assertEquals(2, response.app.customDomains.size)
        assertTrue(response.app.customDomains[0].verified!!)
        assertEquals(1000, response.account.creditsRemaining)
        assertNotNull(gson.toJson(response))
    }
}
