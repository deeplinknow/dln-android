package com.deeplinknow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.matchJson
import com.deeplinknow.DLNTestSupport.matchResponse
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.routingDispatcher
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SDK-85: non-blocking by default; failures are kept in `DLN.lastError` (docs/sdks/overview.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNLastErrorTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    /** What /match answers: "ok" (one link), "empty" (no candidates) or "drop" (network failure). */
    @Volatile
    private var matchMode = "ok"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        mockInstallReferrer(responseCode = InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    private fun serveApp(initCode: Int = 200) {
        server.dispatcher = routingDispatcher(
            mapOf(
                "/api/v1/sdk/init" to {
                    if (initCode == 200) json(initResponse()) else json("""{"error":"Invalid API key"}""", initCode)
                },
                "/api/v1/sdk/match" to {
                    when (matchMode) {
                        "drop" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        "empty" -> json(matchResponse())
                        else -> json(matchResponse(matchJson()))
                    }
                },
            )
        )
    }

    private fun init(apiKey: String = DLNTestSupport.TEST_API_KEY) {
        DLN.init(context, apiKey, baseUrl = server.url("/").toString(), retryAttempts = 0)
        runBlocking { DLN.awaitInitialization() }
    }

    @Test
    fun `SDK-85 lastError is null before any call`() {
        assertNull(DLN.lastError)
    }

    @Test
    fun `SDK-85 AND-34 a 401 init sets lastError to invalidApiKey without throwing`() {
        serveApp(initCode = 401)
        init("bad")
        val error = DLN.lastError
        assertNotNull(error)
        assertEquals(DLNErrorCode.INVALID_API_KEY, error!!.code)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun `SDK-85 a check after a failed init records notInitialized carrying the init failure`() {
        serveApp(initCode = 401)
        init("bad")
        val result = runBlocking { DLN.checkDeferredDeepLink() }
        assertTrue(result.isFailure)
        assertEquals(DLNErrorCode.NOT_INITIALIZED, DLN.lastError?.code)
        assertEquals(DLNErrorCode.INVALID_API_KEY, (DLN.lastError?.cause as DLNException).code)
    }

    @Test
    fun `SDK-85 a network failure in checkDeferredDeepLink sets lastError, a later successful check clears it`() {
        serveApp()
        init()
        assertNull(DLN.lastError)

        matchMode = "drop"
        val failed = runBlocking { DLN.checkDeferredDeepLink() }
        assertTrue(failed.isFailure)
        assertEquals(DLNErrorCode.NETWORK, DLN.lastError?.code)

        matchMode = "ok"
        val found = runBlocking { DLN.checkDeferredDeepLink() }
        assertNotNull(found.getOrNull())
        assertNull(DLN.lastError)
    }

    @Test
    fun `SDK-85 no link found is a success and leaves lastError null`() {
        serveApp()
        init()
        matchMode = "drop"
        runBlocking { DLN.checkDeferredDeepLink() }
        assertEquals(DLNErrorCode.NETWORK, DLN.lastError?.code)

        matchMode = "empty"
        val result = runBlocking { DLN.checkDeferredDeepLink() }
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertNull(DLN.lastError)
    }

    @Test
    fun `SDK-85 AND-34 the callback overload records lastError too`() {
        serveApp()
        init()
        matchMode = "drop"
        val latch = java.util.concurrent.CountDownLatch(1)
        DLN.checkDeferredDeepLink { _: Result<DeferredDeepLink?> -> latch.countDown() }
        val deadline = System.currentTimeMillis() + 5_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(0L, latch.count)
        assertEquals(DLNErrorCode.NETWORK, DLN.lastError?.code)
    }

    @Test
    fun `SDK-85 AND-34 a successful init does not clear a check failure, only a successful check does`() {
        serveApp()
        runBlocking { DLN.checkDeferredDeepLink() } // never initialized: records notInitialized
        assertEquals(DLNErrorCode.NOT_INITIALIZED, DLN.lastError?.code)
        init()
        assertEquals(DLNErrorCode.NOT_INITIALIZED, DLN.lastError?.code)
    }

    @Test
    fun `SDK-85 findDeferredUserResult failures set lastError and a success clears it`() {
        val beforeInit = runBlocking { DLN.findDeferredUserResult() }
        assertTrue(beforeInit.isFailure)
        assertEquals(DLNErrorCode.NOT_INITIALIZED, DLN.lastError?.code)

        serveApp()
        init()
        matchMode = "empty"
        val ok = runBlocking { DLN.findDeferredUserResult() }
        assertTrue(ok.isSuccess)
        assertNull(DLN.lastError)
    }
}
