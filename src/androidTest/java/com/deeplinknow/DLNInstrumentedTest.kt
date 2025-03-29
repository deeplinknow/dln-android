package com.deeplinknow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DLNInstrumentedTest {
    
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // Initialize the SDK for testing
        DLN.init(
            context = context,
            apiKey = "test-api-key",
            enableLogs = true
        )
    }
    
    @Test
    fun testSDKInitialization() {
        val instance = DLN.getInstance()
        assertNotNull(instance)
    }
    
    @Test
    fun testDeepLinkParsing() {
        // This test assumes that deeplinknow.com is in the valid domains list
        val url = "https://deeplinknow.com/product/123?referrer=social_share&campaign=summer"
        val result = DLN.getInstance().parseDeepLink(url)
        
        assertNotNull("Deep link parsing should succeed for valid domain", result)
        assertEquals("/product/123", result!!.first)
        assertEquals("social_share", result.second["referrer"])
        assertEquals("summer", result.second["campaign"])
    }
    
    @Test
    fun testInvalidDomainDeepLinkParsing() {
        val url = "https://invalid-domain.com/product/123"
        val result = DLN.getInstance().parseDeepLink(url)
        assertNull("Deep link parsing should fail for invalid domain", result)
    }
    
    @Test
    fun testDeviceContext() {
        val packageName = context.packageName
        assertNotNull("Package name should be accessible", packageName)
    }

    @Test
    fun testClipboardChecking() = runBlocking {
        // Test empty clipboard
        clipboardManager.clearPrimaryClip()
        assertFalse(DLN.getInstance().hasDeepLinkToken())
        assertNull(DLN.getInstance().checkClipboard())

        // Test invalid content
        val invalidClip = ClipData.newPlainText("text", "https://invalid-domain.com")
        clipboardManager.setPrimaryClip(invalidClip)
        assertFalse(DLN.getInstance().hasDeepLinkToken())
        assertNull(DLN.getInstance().checkClipboard())

        // Test DLN protocol
        val dlnClip = ClipData.newPlainText("text", "dln://some-path")
        clipboardManager.setPrimaryClip(dlnClip)
        assertTrue(DLN.getInstance().hasDeepLinkToken())

        // Test valid domain
        val validClip = ClipData.newPlainText("text", "https://deeplinknow.com/path")
        clipboardManager.setPrimaryClip(validClip)
        assertNotNull(DLN.getInstance().checkClipboard())
    }

    @Test
    fun testFingerprintGeneration() = runBlocking {
        val instance = DLN.getInstance()
        val matchResponse = instance.findDeferredUser()

        // Even though the API call might fail in tests, we can verify the request structure
        assertNotNull("Instance should be available", instance)
        
        // Test that screen metrics are included in fingerprint
        val fingerprintField = DLN::class.java.getDeclaredMethod("getFingerprint")
        fingerprintField.isAccessible = true
        val fingerprint = fingerprintField.invoke(instance) as Fingerprint

        assertNotNull("Screen width should be included", fingerprint.screenWidth)
        assertNotNull("Screen height should be included", fingerprint.screenHeight)
        assertNotNull("Pixel ratio should be included", fingerprint.pixelRatio)
        assertNotNull("Hardware fingerprint should be generated", fingerprint.hardwareFingerprint)
        assertEquals("Platform should be android", "android", fingerprint.platform)
        assertTrue("User agent should follow web format", 
            fingerprint.userAgent.contains("Mozilla/5.0") && 
            fingerprint.userAgent.contains("Android") && 
            fingerprint.userAgent.contains("Chrome"))
    }

    // Note: Tests that require network calls would be marked with @LargeTest
    // and potentially use mock web servers to simulate API responses
}