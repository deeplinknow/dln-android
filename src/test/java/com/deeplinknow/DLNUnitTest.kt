package com.deeplinknow

import android.content.Context
import android.net.Uri
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeepLinkNowUnitTest {
    
    private lateinit var deepLinkNow: DeepLinkNow
    private lateinit var mockContext: Context
    
    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        
        // Mock Settings.Secure.getString for device ID
        mockkStatic(Settings.Secure::class)
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-device-id"
        
        // Initialize the SDK
        DeepLinkNow.initialize(
            context = mockContext,
            apiKey = "test-api-key",
            config = mapOf("enableLogs" to true)
        )
        
        deepLinkNow = DeepLinkNow.getInstance()
        
        // Use reflection to set validDomains for testing
        val validDomainsField: Field = DeepLinkNow::class.java.getDeclaredField("validDomains")
        validDomainsField.isAccessible = true
        val validDomains = validDomainsField.get(deepLinkNow) as MutableSet<String>
        validDomains.add("deeplinknow.com")
        validDomains.add("test-domain.com")
    }
    
    @Test
    fun `test isValidDomain with valid domain`() {
        assertTrue(deepLinkNow.isValidDomain("deeplinknow.com"))
        assertTrue(deepLinkNow.isValidDomain("test-domain.com"))
    }
    
    @Test
    fun `test isValidDomain with invalid domain`() {
        assertFalse(deepLinkNow.isValidDomain("invalid-domain.com"))
    }
    
    @Test
    fun `test parseDeepLink with valid URL`() {
        val url = "https://deeplinknow.com/product/123?referrer=social_share&campaign=summer"
        val result = deepLinkNow.parseDeepLink(url)
        
        assertNotNull(result)
        assertEquals("/product/123", result?.first)
        assertEquals(mapOf("referrer" to "social_share", "campaign" to "summer"), result?.second)
    }
    
    @Test
    fun `test parseDeepLink with invalid domain`() {
        val url = "https://invalid-domain.com/product/123?referrer=social_share"
        val result = deepLinkNow.parseDeepLink(url)
        
        assertNull(result)
    }
    
    @Test
    fun `test parseDeepLink with malformed URL`() {
        val url = "not-a-url"
        val result = deepLinkNow.parseDeepLink(url)
        
        assertNull(result)
    }
}