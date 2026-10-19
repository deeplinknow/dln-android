package com.deeplinknow

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.redirectTo
import com.deeplinknow.DLNTestSupport.routingDispatcher
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Initialization against docs/sdks/android.md (AND-05..10) and docs/sdks/overview.md
 * (SDK-01..09, SDK-55, SDK-63/64), server contract docs/api/sdk-endpoints.md §2.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNInitContractTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

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

    private fun initWith(vararg responses: MockResponse, retryAttempts: Int = 2): Pair<DLN, List<okhttp3.HttpUrl>> {
        responses.forEach { server.enqueue(it) }
        val dln = newDln(context, retryAttempts = retryAttempts)
        val urls = redirectTo(dln, server)
        runBlocking { dln.startAndAwait() }
        return dln to urls
    }

    private fun initError(dln: DLN): DLNException =
        runBlocking { dln.awaitInit() }.exceptionOrNull() as DLNException

    private fun serverBaseUrl() = server.url("/").toString()

    // ---- Request contract ----

    @Test
    fun `SDK-02 init is a POST to https deeplinknow_com api v1 sdk init with x-api-key header and no key in the URL`() {
        val (_, urls) = initWith(json(initResponse()))

        assertEquals("https://deeplinknow.com/api/v1/sdk/init", urls.single().toString())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sdk/init", request.path)
        assertEquals(DLNTestSupport.TEST_API_KEY, request.getHeader("x-api-key"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertFalse(request.path!!.contains(DLNTestSupport.TEST_API_KEY))
        // Server ignores the body; it must be valid JSON
        DLNTestSupport.parseObject(request.body.readUtf8())
    }

    @Test
    fun `BILL-20 init body carries device_id (ANDROID_ID, same as match) or an explicit null`() {
        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID, "a1b2c3d4e5f60718")
        initWith(json(initResponse()))
        val body = DLNTestSupport.parseObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("a1b2c3d4e5f60718", body["device_id"].asString)
        // FP-21: the SDK names itself so the server can see which versions count MAU.
        assertEquals("android/${DLN.SDK_VERSION}", body["sdk"].asString)
        assertFalse(body.toString().contains(DLNTestSupport.TEST_API_KEY))

        android.provider.Settings.Secure.putString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID, null)
        initWith(json(initResponse()))
        val nullBody = DLNTestSupport.parseObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
        assertTrue(nullBody.has("device_id"))
        assertTrue(nullBody["device_id"].isJsonNull)
    }

    @Test
    fun `SDK-01 AND-07 baseUrl is configurable and a trailing slash is tolerated`() {
        for (base in listOf(serverBaseUrl(), serverBaseUrl().trimEnd('/'))) {
            server.enqueue(json(initResponse()))
            val dln = newDln(context, baseUrl = base)
            runBlocking { dln.startAndAwait() }
            assertEquals("/api/v1/sdk/init", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test
    fun `SDK-01 AND-07 DLNConfig defaults - logs off, deeplinknow_com, 10s timeout, 2 retries`() {
        val config = DLNConfig(apiKey = "k")
        assertFalse(config.enableLogs)
        assertEquals("https://deeplinknow.com", config.baseUrl)
        assertEquals(10_000L, config.timeoutMs)
        assertEquals(2, config.retryAttempts)
    }

    @Test
    fun `AND-07 SDK-52 timeoutMs is applied to the OkHttp client`() {
        val dln = DLN(DLNConfig(apiKey = "k", timeoutMs = 1234), context)
        assertEquals(1234, dln.client.connectTimeoutMillis)
        assertEquals(1234, dln.client.readTimeoutMillis)
        assertEquals(1234, dln.client.callTimeoutMillis)
    }

    @Test
    fun `SDK-52 a request that exceeds the timeout fails with network`() {
        val dln = newDln(context, retryAttempts = 0, baseUrl = serverBaseUrl())
        dln.client = dln.client.newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(json(initResponse()).setHeadersDelay(2, TimeUnit.SECONDS))
        runBlocking { dln.startAndAwait() }
        assertEquals(DLNErrorCode.NETWORK, initError(dln).code)
    }

    @Test
    fun `SDK-64 a non-https baseUrl is refused unless it is a development host`() {
        val dln = newDln(context, baseUrl = "http://example.com")
        runBlocking { dln.startAndAwait() }
        assertTrue(runBlocking { dln.awaitInit() }.isFailure)
        assertEquals(0, server.requestCount)
        // localhost is allowed (development)
        server.enqueue(json(initResponse()))
        val local = newDln(context, baseUrl = serverBaseUrl())
        runBlocking { local.startAndAwait() }
        assertTrue(runBlocking { local.awaitInit() }.isSuccess)
    }

    // ---- Response handling ----

    @Test
    fun `SDK-03 init stores app id, alias, link domains, account status and credits, ignoring unknown fields`() {
        val (dln, _) = initWith(json(initResponse()))
        val info = runBlocking { dln.awaitInit() }.getOrThrow()
        assertEquals(DLNTestSupport.APP_ID, info.appId)
        assertEquals("myapp", info.alias)
        assertEquals(setOf("myapp.deeplinknow.com", "links.myapp.com"), info.linkDomains)
        assertEquals("active", info.accountStatus)
        assertEquals(1000, info.creditsRemaining)
        val response = dln.initResponse!!
        assertEquals("com.myapp", response.app.androidPackageName)
        assertEquals("AA:BB:CC", response.app.androidSha256Cert)
        assertNull(response.app.iosBundleId)
        assertEquals(1, response.account.rateLimits.matchesPerSecond)
        assertEquals(333, response.account.rateLimits.matchesPerDay)
    }

    @Test
    fun `SDK-03 custom domains are lowercased`() {
        val (dln, _) = initWith(json(initResponse("Links.MyApp.COM", true)))
        assertTrue(runBlocking { dln.awaitInit() }.getOrThrow().linkDomains.contains("links.myapp.com"))
        assertTrue(dln.isValidDomain("LINKS.myapp.com"))
    }

    @Test
    fun `SDK-03 verified custom domain from init is accepted by isValidDomain`() {
        val (dln, _) = initWith(json(initResponse("links.myapp.com", true)))
        assertTrue(dln.isValidDomain("links.myapp.com"))
        assertEquals("/promo", dln.parseDeepLink("https://links.myapp.com/promo?code=SAVE20")!!.first)
    }

    @Test
    fun `SDK-03 unverified custom domain is not accepted`() {
        val (dln, _) = initWith(json(initResponse("links.myapp.com", false)))
        assertFalse(dln.isValidDomain("links.myapp.com"))
    }

    @Test
    fun `SDK-03 app without a custom domain (domain null, verified false) is handled`() {
        val (dln, _) = initWith(json(initResponse(customDomain = null, verified = false)))
        assertFalse(dln.isValidDomain("null"))
        assertTrue(dln.isValidDomain("myapp.deeplinknow.com"))
    }

    @Test
    fun `SDK-03 verified null is treated as unverified`() {
        val (dln, _) = initWith(json(initResponse("links.myapp.com", null)))
        assertFalse(dln.isValidDomain("links.myapp.com"))
    }

    @Test
    fun `SDK-37 custom domain is not trusted before init completes`() {
        val dln = newDln(context)
        assertFalse(dln.isValidDomain("links.myapp.com"))
    }

    // ---- Errors (SDK-04, SDK-55) ----

    @Test
    fun `SDK-04 SDK-55 invalid API key (401) is reported as invalidApiKey and adds no domains`() {
        val (dln, _) = initWith(json("""{"error":"Invalid API key"}""", 401))
        val error = initError(dln)
        assertEquals(DLNErrorCode.INVALID_API_KEY, error.code)
        assertEquals(401, error.httpStatus)
        assertEquals("Invalid API key", error.serverMessage)
        assertFalse(dln.isValidDomain("links.myapp.com"))
        assertFalse(dln.isValidDomain("deeplinknow.com"))
        assertFalse(dln.isInitializedSuccessfully)
    }

    @Test
    fun `SDK-55 403 inactive account, 403 no credits, 429, 500 and 400 map to typed codes`() {
        val cases = listOf(
            json("""{"error":"Account is not active","status":"suspended"}""", 403) to DLNErrorCode.ACCOUNT_INACTIVE,
            json("""{"error":"No credits remaining"}""", 403) to DLNErrorCode.NO_CREDITS,
            json("""{"error":"Rate limit exceeded. Maximum 60 requests per minute."}""", 429) to DLNErrorCode.RATE_LIMITED,
            json("""{"error":"Internal server error"}""", 500) to DLNErrorCode.SERVER,
            json("""{"error":"bad"}""", 400) to DLNErrorCode.BAD_REQUEST,
        )
        for ((response, code) in cases) {
            val (dln, _) = initWith(response, retryAttempts = 0)
            assertEquals(code, initError(dln).code)
        }
    }

    @Test
    fun `SDK-55 error codes have the cross-platform wire names`() {
        assertEquals(
            listOf("notInitialized", "invalidApiKey", "accountInactive", "noCredits", "rateLimited", "badRequest", "server", "network", "invalidResponse"),
            DLNErrorCode.values().map { it.value },
        )
    }

    @Test
    fun `SDK-54 network failure during init is reported as network and does not throw`() {
        val (dln, _) = initWith(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            retryAttempts = 0,
        )
        assertEquals(DLNErrorCode.NETWORK, initError(dln).code)
    }

    @Test
    fun `SDK-51 AND-08 malformed 200 response (captive portal HTML) is invalidResponse, never a throw`() {
        try {
            val (dln, _) = initWith(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                    .setBody("<html><body>Sign in to Wi-Fi</body></html>")
            )
            assertEquals(DLNErrorCode.INVALID_RESPONSE, initError(dln).code)
        } catch (e: Exception) {
            fail("init threw on a non-JSON 200 body: $e")
        }
    }

    @Test
    fun `SDK-51 a 200 JSON body missing app is invalidResponse`() {
        val (dln, _) = initWith(json("""{"account":{"status":"active"}}"""))
        assertEquals(DLNErrorCode.INVALID_RESPONSE, initError(dln).code)
    }

    // ---- Retries (SDK-53) ----

    @Test
    fun `SDK-53 5xx and 429 are retried up to retryAttempts then succeed`() {
        val (dln, _) = initWith(
            json("""{"error":"Internal server error"}""", 500),
            json("""{"error":"Rate limit exceeded"}""", 429),
            json(initResponse()),
        )
        assertTrue(runBlocking { dln.awaitInit() }.isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `SDK-53 401, 403 and 400 are not retried`() {
        for (code in listOf(400, 401, 403)) {
            val before = server.requestCount
            initWith(json("""{"error":"x"}""", code))
            assertEquals("status $code", before + 1, server.requestCount)
        }
    }

    @Test
    fun `SDK-53 retries stop after retryAttempts`() {
        val (dln, _) = initWith(
            json("{}", 503), json("{}", 503), json("{}", 503), json(initResponse()),
            retryAttempts = 2,
        )
        assertEquals(DLNErrorCode.SERVER, initError(dln).code)
        assertEquals(3, server.requestCount)
    }

    // ---- Init outcome API (AND-06, SDK-04/05) ----

    @Test
    fun `AND-06 SDK-05 isInitialized and awaitInitialization report the outcome of DLN_init`() {
        server.enqueue(json(initResponse()))
        assertFalse(DLN.isInitialized)
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        val result = runBlocking { DLN.awaitInitialization() }
        assertTrue(result.isSuccess)
        assertEquals("myapp", result.getOrThrow().alias)
        assertTrue(DLN.isInitialized)
    }

    @Test
    fun `AND-06 SDK-56 onComplete is called once on the main thread with the outcome`() {
        server.enqueue(json("""{"error":"Invalid API key"}""", 401))
        val results = mutableListOf<Result<DLNAppInfo>>()
        var onMain = false
        DLN.init(context, "bad", baseUrl = serverBaseUrl(), onComplete = {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            results += it
        })
        runBlocking { DLN.awaitInitialization() }
        val deadline = System.currentTimeMillis() + 5_000
        while (results.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
        assertTrue(onMain)
        assertEquals(DLNErrorCode.INVALID_API_KEY, (results[0].exceptionOrNull() as DLNException).code)
        assertFalse(DLN.isInitialized)
    }

    @Test
    fun `AND-06 SDK-04 new static entry points do not throw before init and report notInitialized`() {
        val init = runBlocking { DLN.awaitInitialization() }
        assertEquals(DLNErrorCode.NOT_INITIALIZED, (init.exceptionOrNull() as DLNException).code)
        val link = runBlocking { DLN.checkDeferredDeepLink() }
        assertEquals(DLNErrorCode.NOT_INITIALIZED, (link.exceptionOrNull() as DLNException).code)
        val matches = runBlocking { DLN.findDeferredUserResult() }
        assertEquals(DLNErrorCode.NOT_INITIALIZED, (matches.exceptionOrNull() as DLNException).code)
        DLN.resetDeferredDeepLink()
        assertNull(DLN.parseDeepLink("https://evil.com/x"))
    }

    @Test
    fun `SDK-04 after a failed init checkDeferredDeepLink reports notInitialized carrying the init failure`() {
        server.enqueue(json("""{"error":"Invalid API key"}""", 401))
        DLN.init(context, "bad", baseUrl = serverBaseUrl())
        val result = runBlocking { DLN.checkDeferredDeepLink() }
        val error = result.exceptionOrNull() as DLNException
        assertEquals(DLNErrorCode.NOT_INITIALIZED, error.code)
        assertEquals(DLNErrorCode.INVALID_API_KEY, (error.cause as DLNException).code)
    }

    @Test
    fun `SDK-06 checkDeferredDeepLink called while init is in flight waits for init`() {
        server.dispatcher = routingDispatcher(
            mapOf(
                "/api/v1/sdk/init" to { json(initResponse()).setHeadersDelay(300, TimeUnit.MILLISECONDS) },
                "/api/v1/sdk/match" to { json(DLNTestSupport.matchResponse()) },
            )
        )
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        val result = runBlocking { DLN.checkDeferredDeepLink() }
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertNull(result.getOrNull())
    }

    // ---- Idempotency (AND-09, SDK-07/08) ----

    @Test
    fun `AND-09 SDK-07 SDK-08 repeated DLN_init with the same key makes exactly one init request`() {
        val inits = AtomicInteger()
        server.dispatcher = routingDispatcher(
            mapOf("/api/v1/sdk/init" to { inits.incrementAndGet(); json(initResponse()) })
        )
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        val first = DLN.getInstance()
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        runBlocking { DLN.awaitInitialization() }
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        runBlocking { DLN.awaitInitialization() }
        assertEquals(1, inits.get())
        assertTrue(first === DLN.getInstance())
    }

    @Test
    fun `SDK-07 DLN_init with a different key replaces the previous state`() {
        server.dispatcher = routingDispatcher(mapOf("/api/v1/sdk/init" to { json(initResponse()) }))
        DLN.init(context, "key-one", baseUrl = serverBaseUrl())
        val first = DLN.getInstance()
        DLN.init(context, "key-two", baseUrl = serverBaseUrl())
        assertTrue(first !== DLN.getInstance())
        assertTrue(runBlocking { DLN.awaitInitialization() }.isSuccess)
    }

    @Test
    fun `AND-09 deprecated initializeSDK reuses the running init instead of a second request`() {
        server.enqueue(json(initResponse()))
        val dln = newDln(context)
        redirectTo(dln, server)
        @Suppress("DEPRECATION")
        runBlocking {
            dln.initializeSDK()
            dln.initializeSDK()
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `SDK-09 init returns without blocking and does not touch the clipboard`() {
        server.enqueue(json(initResponse()).setHeadersDelay(1, TimeUnit.SECONDS))
        val start = System.nanoTime()
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
        runBlocking { DLN.awaitInitialization() }
        assertFalse(DLN.getInstance().clipboardRead)
    }

    // ---- Logging and the key (AND-10, SDK-63, SDK-70/71) ----

    @Test
    fun `AND-10 SDK-63 DLNConfig toString masks the API key`() {
        val text = DLNConfig(apiKey = "dln_pub_SECRETSECRETabcd").toString()
        assertFalse(text, text.contains("SECRETSECRET"))
        assertTrue(text, text.contains("abcd"))
    }

    @Test
    fun `AND-10 SDK-70 SDK-71 logs are gated by enableLogs and never contain the key`() {
        ShadowLog.clear()
        server.enqueue(json(initResponse()))
        server.enqueue(json(DLNTestSupport.matchResponse()))
        val quiet = newDln(context, enableLogs = false, baseUrl = serverBaseUrl())
        runBlocking { quiet.startAndAwait(); quiet.findDeferredUser() }
        val debugLines = ShadowLog.getLogs().filter { it.type == android.util.Log.DEBUG && it.tag.startsWith("DLN") }
        assertTrue(debugLines.joinToString { "${it.tag}: ${it.msg}" }, debugLines.isEmpty())

        ShadowLog.clear()
        server.enqueue(json(initResponse()))
        server.enqueue(json(DLNTestSupport.matchResponse()))
        val loud = newDln(context, enableLogs = true, baseUrl = serverBaseUrl())
        runBlocking { loud.startAndAwait(); loud.findDeferredUser() }
        val logs = ShadowLog.getLogs().filter { it.tag.startsWith("DLN") }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.joinToString { it.tag }, logs.all { it.tag == "DLN" })
        assertTrue(logs.none { it.msg.contains(DLNTestSupport.TEST_API_KEY) })
    }

    // ---- Calls before init ----

    @Test
    fun `AND-06 getInstance before init keeps throwing IllegalStateException`() {
        try {
            DLN.getInstance()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("DLN not initialized", e.message)
        }
    }

    @Test
    fun `handleDeepLink before init returns false`() {
        assertFalse(DLN.handleDeepLink("https://myapp.deeplinknow.com/promo"))
    }

    @Test
    fun `callback set before init is registered once the instance starts`() {
        val received = mutableListOf<DeepLinkData>()
        DLN.setDeepLinkCallback(object : DeepLinkCallback {
            override fun onDeepLinkOpen(deepLinkData: DeepLinkData) {
                received += deepLinkData
            }
        })
        val (dln, _) = initWith(json(initResponse()))
        DLNTestSupport.setSingleton(dln)

        assertTrue(DLN.handleDeepLink("https://myapp.deeplinknow.com/promo/summer?code=SAVE20"))
        assertEquals(1, received.size)
        assertEquals("promo/summer", received[0].route)
        assertEquals(mapOf("code" to "SAVE20"), received[0].params)
    }

    // ---- Lifetime (AND-05) ----

    @Test
    fun `AND-05 DLN_init stores only the application context`() {
        server.enqueue(json(initResponse()))
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = serverBaseUrl())
        assertTrue(DLN.getInstance().appContext === context.applicationContext)
    }

    @Test
    fun `AND-05 instance created by DLN_init survives garbage collection after init finishes`() {
        // No MockK here: MockK's recorded calls would keep the referrer listener (and so the
        // DLN instance) reachable.
        unmockkAll()
        server.enqueue(json(initResponse()))
        DLN.init(context, "dln_pk_test_gc", baseUrl = serverBaseUrl())
        runBlocking { DLN.awaitInitialization() }
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            System.gc()
            Thread.sleep(50)
            try {
                DLN.getInstance()
            } catch (e: IllegalStateException) {
                fail("DLN instance was garbage collected after init: ${e.message}")
            }
        }
        assertNotNull(DLN.getInstance())
    }
}
