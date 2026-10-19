package com.deeplinknow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.redirectTo
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SDK-83: a cold start within 24 hours of the last /init, in the same UTC month, initializes
 * from the device cache with no network request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNInitCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var clock = 1_792_000_000_000L // 2026-10-15 (UTC)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        mockInstallReferrer(responseCode = InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
        cacheFile().delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
        DLNTestSupport.resetStatics()
        cacheFile().delete()
    }

    private fun cacheFile() = File(context.noBackupFilesDir, "com.deeplinknow.initcache.json")

    /** A cold start: a new process (statics reset) with the files on disk kept. */
    private fun launch(apiKey: String = DLNTestSupport.TEST_API_KEY, enqueue: Boolean = true): DLN {
        DLN.resetForTesting()
        if (enqueue) server.enqueue(json(initResponse()))
        val dln = newDln(context, apiKey = apiKey)
        dln.now = { clock }
        redirectTo(dln, server)
        runBlocking { dln.startAndAwait() }
        return dln
    }

    private fun utc(text: String): Long = java.time.Instant.parse(text).toEpochMilli()

    @Test
    fun `SDK-83 a cold start within 24 hours uses the cache`() {
        val first = runBlocking { launch().awaitInit() }.getOrThrow()
        assertEquals(1, server.requestCount)
        assertTrue(cacheFile().exists())

        clock += 23 * 3_600_000L
        val second = launch(enqueue = false)
        assertEquals("no /init request", 1, server.requestCount)
        assertEquals(first, runBlocking { second.awaitInit() }.getOrThrow())
        assertTrue(second.isValidDomain("links.myapp.com"))
    }

    @Test
    fun `SDK-83 after 24 hours it asks the server again`() {
        launch()
        clock += 24 * 3_600_000L + 1
        launch()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `SDK-83 BILL-19 a new UTC month always asks the server`() {
        clock = utc("2026-10-31T23:00:00Z")
        launch()
        clock = utc("2026-11-01T01:00:00Z")
        launch()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `SDK-83 a different key ignores the cache`() {
        launch()
        launch(apiKey = "dln_pk_other_0123456789abcdef")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `SDK-83 SDK-07 a second init in the same process always asks the server`() {
        launch()
        DLN.resetForTesting()
        val cold = newDln(context).also { it.now = { clock }; redirectTo(it, server) }
        runBlocking { cold.startAndAwait() } // from cache
        assertEquals(1, server.requestCount)
        server.enqueue(json(initResponse(customDomain = null)))
        val again = newDln(context).also { it.now = { clock }; redirectTo(it, server) }
        runBlocking { again.startAndAwait() } // same process: network
        assertEquals(2, server.requestCount)
        assertFalse(again.isValidDomain("links.myapp.com"))
    }

    @Test
    fun `SDK-83 a rejected key clears the cache`() {
        launch()
        assertTrue(cacheFile().exists())
        clock += 25 * 3_600_000L
        DLN.resetForTesting()
        server.enqueue(json("""{"error":"Invalid API key"}""", 401))
        val dln = newDln(context).also { it.now = { clock }; redirectTo(it, server) }
        runBlocking { dln.startAndAwait() }
        assertTrue(runBlocking { dln.awaitInit() }.isFailure)
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `SDK-83 SDK-26 resetDeferredDeepLink clears the cache`() {
        val dln = launch()
        DLN.instance = dln
        DLN.resetDeferredDeepLink()
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `SDK-83 SDK-65 the cache holds only the key, base URL, time and the init response`() {
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID, "a1b2c3d4e5f60718")
        launch()
        val cache = DLNTestSupport.parseObject(cacheFile().readText())
        assertEquals(setOf("format", "api_key", "base_url", "saved_at", "response"), cache.keySet())
        assertFalse(cacheFile().readText().contains("a1b2c3d4e5f60718"))
    }
}
