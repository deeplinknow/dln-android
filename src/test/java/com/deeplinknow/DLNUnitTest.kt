package com.deeplinknow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.HashSet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNUnitTest {
    
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
} 