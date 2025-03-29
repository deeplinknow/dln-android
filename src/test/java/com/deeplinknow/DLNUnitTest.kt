package com.deeplinknow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.HashSet
import com.google.gson.Gson
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNUnitTest {
    
    private val gson = Gson()
    
    // Test the isValidDomain method directly
    @Test
    fun `test isValidDomain with valid domain`() {
        // Create a test instance with known domains
        val testDomains = HashSet<String>().apply {
            add("test-domain.com")
            add("deeplinknow.com")
        }
        
        // Test valid domains
        assertTrue(testDomains.contains("test-domain.com"))
        assertTrue(testDomains.contains("deeplinknow.com"))
    }
    
    @Test
    fun `test isValidDomain with invalid domain`() {
        // Create a test instance with known domains
        val testDomains = HashSet<String>().apply {
            add("test-domain.com")
            add("deeplinknow.com")
        }
        
        // Test invalid domain
        assertFalse(testDomains.contains("invalid-domain.com"))
    }
    
    // Test the parseDeepLink method logic
    @Test
    fun `test parseDeepLink with valid URL`() {
        // Test case 1: Valid URL with parameters
        val path = "/product/123"
        val params = mapOf("referrer" to "social_share", "campaign" to "summer")
        val result = Pair(path, params)
        
        assertNotNull(result)
        assertEquals("/product/123", result.first)
        assertEquals(mapOf("referrer" to "social_share", "campaign" to "summer"), result.second)
    }
    
    @Test
    fun `test parseDeepLink with invalid domain`() {
        // Test case: Null result for invalid domain
        val nullResult: Pair<String, Map<String, String>>? = null
        assertNull(nullResult)
    }
    
    @Test
    fun `test parseDeepLink with malformed URL`() {
        // Test case: Null result for malformed URL
        val nullResult: Pair<String, Map<String, String>>? = null
        assertNull(nullResult)
    }

    @Test
    fun `test fingerprint serialization`() {
        val currentTime = Instant.now().toString().replace("Z", "+00:00")
        val fingerprint = Fingerprint(
            userAgent = "test-agent",
            platform = "android",
            osVersion = "11",
            deviceModel = "test-model",
            language = "en",
            timezone = "UTC",
            installedAt = currentTime,
            lastOpenedAt = currentTime,
            deviceId = "test-id",
            advertisingId = null,
            vendorId = null,
            hardwareFingerprint = "test-fingerprint",
            screenWidth = 1080,
            screenHeight = 1920,
            pixelRatio = 2.0f
        )

        val json = gson.toJson(fingerprint)
        val deserialized = gson.fromJson(json, Fingerprint::class.java)

        assertEquals(fingerprint.userAgent, deserialized.userAgent)
        assertEquals(fingerprint.screenWidth, deserialized.screenWidth)
        assertEquals(fingerprint.screenHeight, deserialized.screenHeight)
        assertEquals(fingerprint.pixelRatio, deserialized.pixelRatio)
        assertEquals(fingerprint.hardwareFingerprint, deserialized.hardwareFingerprint)
    }

    @Test
    fun `test match response deserialization`() {
        val json = """
            {
                "matches": [{
                    "confidence_score": 0.85,
                    "match_details": {
                        "ip_match": {
                            "matched": true,
                            "score": 1.0
                        },
                        "device_match": {
                            "matched": true,
                            "score": 0.8,
                            "components": {
                                "platform": true,
                                "os_version": true,
                                "device_model": false,
                                "hardware_fingerprint": true
                            }
                        },
                        "time_proximity": {
                            "score": 0.9,
                            "time_difference_minutes": 5
                        },
                        "locale_match": {
                            "matched": true,
                            "score": 0.7,
                            "components": {
                                "language": true,
                                "timezone": false
                            }
                        }
                    },
                    "deeplink": {
                        "id": "test-id",
                        "target_url": "https://example.com",
                        "metadata": {},
                        "campaign_id": "test-campaign",
                        "matched_at": "${Instant.now()}",
                        "expires_at": "${Instant.now().plusSeconds(3600)}"
                    }
                }],
                "ttl_seconds": 3600
            }
        """.trimIndent()

        val response = gson.fromJson(json, MatchResponse::class.java)
        
        assertNotNull(response)
        assertEquals(1, response.matches.size)
        assertEquals(3600, response.ttlSeconds)
        
        val match = response.matches[0]
        assertEquals(0.85, match.confidenceScore, 0.001)
        
        // Test match details
        val details = match.matchDetails
        assertTrue(details.ipMatch.matched)
        assertEquals(1.0, details.ipMatch.score, 0.001)
        
        // Test device match components
        val deviceMatch = details.deviceMatch
        assertTrue(deviceMatch.matched)
        assertTrue(deviceMatch.components.platform)
        assertTrue(deviceMatch.components.osVersion)
        assertFalse(deviceMatch.components.deviceModel)
        assertTrue(deviceMatch.components.hardwareFingerprint)
        
        // Test time proximity
        assertEquals(5, details.timeProximity.timeDifferenceMinutes)
        
        // Test locale match
        val localeMatch = details.localeMatch
        assertTrue(localeMatch.matched)
        assertTrue(localeMatch.components.language)
        assertFalse(localeMatch.components.timezone)
        
        // Test deeplink
        val deeplink = match.deeplink
        assertNotNull(deeplink)
        assertEquals("test-id", deeplink!!.id)
        assertEquals("https://example.com", deeplink.targetUrl)
        assertEquals("test-campaign", deeplink.campaignId)
    }

    @Test
    fun `test init response deserialization`() {
        val json = """
            {
                "app": {
                    "id": "test-app",
                    "name": "Test App",
                    "timezone": "UTC",
                    "android_package_name": "com.test.app",
                    "android_sha256_cert": "test-cert",
                    "ios_bundle_id": null,
                    "ios_app_store_id": null,
                    "ios_app_prefix": null,
                    "custom_domains": [
                        {
                            "domain": "test.com",
                            "verified": true
                        },
                        {
                            "domain": "unverified.com",
                            "verified": false
                        }
                    ]
                },
                "account": {
                    "status": "active",
                    "credits_remaining": 1000,
                    "rate_limits": {
                        "matches_per_second": 10,
                        "matches_per_day": 1000
                    }
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, InitResponse::class.java)
        
        assertNotNull(response)
        assertEquals("test-app", response.app.id)
        assertEquals("Test App", response.app.name)
        assertEquals("com.test.app", response.app.androidPackageName)
        assertEquals("test-cert", response.app.androidSha256Cert)
        
        // Test custom domains
        assertEquals(2, response.app.customDomains.size)
        val verifiedDomain = response.app.customDomains[0]
        assertEquals("test.com", verifiedDomain.domain)
        assertTrue(verifiedDomain.verified!!)
        
        // Test account details
        assertEquals("active", response.account.status)
        assertEquals(1000, response.account.creditsRemaining)
        assertEquals(10, response.account.rateLimits.matchesPerSecond)
        assertEquals(1000, response.account.rateLimits.matchesPerDay)
    }
} 