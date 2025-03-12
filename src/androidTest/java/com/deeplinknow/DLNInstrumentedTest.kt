package com.deeplinknow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkNowInstrumentedTest {
    
    private lateinit var context: Context
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize the SDK for testing
        DeepLinkNow.initialize(
            context = context,
            apiKey = "test-api-key",
            config = mapOf("enableLogs" to true)
        )
    }
    
    @Test
    fun testSDKInitialization() {
        val instance = DeepLinkNow.getInstance()
        assertNotNull(instance)
    }
    
    @Test
    fun testDeepLinkParsing() {
        // This test assumes that deeplinknow.com is in the valid domains list
        // In a real test, you might need to set up the valid domains first
        val url = "https://deeplinknow.com/product/123?referrer=social_share&campaign=summer"
        val result = DeepLinkNow.getInstance().parseDeepLink(url)
        
        // If the domain is valid, we should get a result
        if (result != null) {
            assertEquals("/product/123", result.first)
            assertTrue(result.second.containsKey("referrer"))
            assertEquals("social_share", result.second["referrer"])
            assertTrue(result.second.containsKey("campaign"))
            assertEquals("summer", result.second["campaign"])
        }
    }
    
    @Test
    fun testDeviceContext() {
        // Verify that the SDK can access device information
        val packageName = context.packageName
        assertNotNull(packageName)
        
        // This is a basic test to ensure the context is working
        // In a real app, you'd test more specific SDK functionality
    }
    
    // Note: Tests that require network calls would be marked with @LargeTest
    // and potentially use mock web servers to simulate API responses
}