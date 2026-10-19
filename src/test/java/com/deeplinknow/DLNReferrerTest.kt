package com.deeplinknow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.deeplinknow.DLNTestSupport.DEEPLINK_ID
import com.deeplinknow.DLNTestSupport.FP_ID
import com.deeplinknow.DLNTestSupport.deeplinkJson
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.redirectTo
import com.deeplinknow.DLNTestSupport.referrerLookupSuccess
import com.deeplinknow.DLNTestSupport.routingDispatcher
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Play Install Referrer handling (docs/sdks/android.md §2.5, AND-12..19, SDK-11).
 *
 * Server side: web/components/universal-links.tsx builds
 *   play.google.com/...&referrer=fp_id=<uuid>   (or deeplink_id=<uuid>)
 * and web/app/api/v1/sdk/referrer-lookup/route.ts serves GET ?fp_id= / ?deeplink_id=.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNReferrerTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var lookupResponse: () -> MockResponse = { json(referrerLookupSuccess()) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        server = MockWebServer().apply {
            dispatcher = routingDispatcher(
                mapOf(
                    "/api/v1/sdk/init" to { json(initResponse()) },
                    "/api/v1/sdk/referrer-lookup" to { lookupResponse() },
                    "/api/v1/sdk/match" to { json(DLNTestSupport.matchResponse()) },
                )
            )
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    private fun runInit(): DLN {
        val dln = newDln(context, retryAttempts = 0)
        redirectTo(dln, server)
        runBlocking { dln.startAndAwait() }
        return dln
    }

    private fun recordedRequests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    private fun lookupRequests() =
        recordedRequests().filter { it.requestUrl!!.encodedPath == "/api/v1/sdk/referrer-lookup" }

    // ---- Parsing + request (AND-15, AND-16) ----

    @Test
    fun `AND-16 SDK-11 fp_id referrer calls referrer-lookup with GET, x-api-key and fp_id query`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        runInit()

        val req = lookupRequests().single()
        assertEquals("GET", req.method)
        assertEquals(DLNTestSupport.TEST_API_KEY, req.getHeader("x-api-key"))
        assertEquals(FP_ID, req.requestUrl!!.queryParameter("fp_id"))
        assertNull(req.requestUrl!!.queryParameter("deeplink_id"))
    }

    @Test
    fun `AND-15 deeplink_id referrer calls referrer-lookup with deeplink_id`() {
        mockInstallReferrer(referrer = "deeplink_id=$DEEPLINK_ID")
        runInit()

        val req = lookupRequests().single()
        assertEquals(DEEPLINK_ID, req.requestUrl!!.queryParameter("deeplink_id"))
        assertNull(req.requestUrl!!.queryParameter("fp_id"))
    }

    @Test
    fun `AND-15 fp_id wins when both are present, and other params are ignored`() {
        mockInstallReferrer(referrer = "utm_source=dln&deeplink_id=$DEEPLINK_ID&fp_id=$FP_ID&utm_medium=link")
        val dln = runInit()

        assertEquals(FP_ID, lookupRequests().single().requestUrl!!.queryParameter("fp_id"))
        val data = dln.getReferrerData()!!
        assertEquals(FP_ID, data.fpId)
        assertEquals(DEEPLINK_ID, data.deeplinkId)
    }

    @Test
    fun `AND-15 AND-16 percent-encoded characters in the value reach the server decoded`() {
        mockInstallReferrer(referrer = "fp_id=" + FP_ID.replace("-", "%2D"))
        runInit()
        val req = lookupRequests().single()
        assertEquals(FP_ID, req.requestUrl!!.queryParameter("fp_id"))
    }

    @Test
    fun `AND-15 fully URL-encoded referrer (fp_id%3D) is recognised`() {
        mockInstallReferrer(referrer = "fp_id%3D$FP_ID")
        runInit()
        assertEquals(FP_ID, lookupRequests().singleOrNull()?.requestUrl?.queryParameter("fp_id"))
    }

    @Test
    fun `FLOW-23 AND-15 keys that merely end in fp_id or deeplink_id are not ours`() {
        mockInstallReferrer(referrer = "utm_source=partner&src_deeplink_id=$DEEPLINK_ID&xfp_id=$FP_ID&my_deeplink_id=$DEEPLINK_ID")
        val dln = runInit()
        assertTrue(lookupRequests().isEmpty())
        assertNull(dln.getReferrerData())
    }

    @Test
    fun `AND-15 values that are not UUIDs are treated as no referrer`() {
        mockInstallReferrer(referrer = "fp_id=../../etc&deeplink_id=abc")
        val dln = runInit()
        assertTrue(lookupRequests().isEmpty())
        assertNull(dln.getDeferredDeepLink())
    }

    @Test
    fun `AND-15 an invalid fp_id falls back to a valid deeplink_id`() {
        mockInstallReferrer(referrer = "fp_id=nope&deeplink_id=$DEEPLINK_ID")
        runInit()
        assertEquals(DEEPLINK_ID, lookupRequests().single().requestUrl!!.queryParameter("deeplink_id"))
    }

    // ---- No referrer / organic ----

    @Test
    fun `organic install referrer does not call the server and yields no deferred link`() {
        mockInstallReferrer(referrer = "utm_source=google-play&utm_medium=organic")
        val dln = runInit()
        assertTrue(lookupRequests().isEmpty())
        assertNull(dln.getDeferredDeepLink())
        assertNull(dln.getReferrerData())
    }

    @Test
    fun `empty, null and garbage referrers do not call the server`() {
        for (referrer in listOf("", null, "%%%&&&===", "fp_id=", "deeplink_id")) {
            unmockkAll()
            DLNTestSupport.resetStatics()
            mockInstallReferrer(referrer = referrer)
            val dln = runInit()
            dln.resetLocalStateForTesting()
            assertNull("referrer=$referrer", dln.getDeferredDeepLink())
        }
        assertTrue(lookupRequests().isEmpty())
    }

    // ---- Referrer client (AND-13, AND-14) ----

    @Test
    fun `AND-14 FEATURE_NOT_SUPPORTED is definitive and is not retried`() {
        val client = mockInstallReferrer(responseCode = InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
        val dln = runInit()
        assertNull(dln.getDeferredDeepLink())
        verify(exactly = 1) { client.startConnection(any()) }
        verify { client.endConnection() }
        assertTrue(dln.referrerOutcomeIsDefinitive())
    }

    @Test
    fun `AND-14 SERVICE_UNAVAILABLE and DEVELOPER_ERROR are retried up to 3 attempts, then not cached`() {
        for (code in listOf(InstallReferrerResponse.SERVICE_UNAVAILABLE, InstallReferrerResponse.DEVELOPER_ERROR)) {
            unmockkAll()
            val client = mockInstallReferrer(responseCode = code)
            val dln = runInit()
            assertNull(dln.getDeferredDeepLink())
            verify(exactly = 3) { client.startConnection(any()) }
            verify(atLeast = 3) { client.endConnection() }
            assertTrue(!dln.referrerOutcomeIsDefinitive())
        }
    }

    @Test
    fun `AND-14 a transient failure followed by OK uses the referrer`() {
        mockInstallReferrer(
            referrer = "fp_id=$FP_ID",
            responseCodes = listOf(InstallReferrerResponse.SERVICE_UNAVAILABLE, InstallReferrerResponse.OK),
        )
        val dln = runInit()
        assertEquals(DEEPLINK_ID, dln.getDeferredDeepLink()!!.id)
    }

    @Test
    fun `AND-13 AND-14 a disconnect before setup is transient and resumes once`() {
        val client = mockInstallReferrer(disconnectInsteadOfSetup = true)
        val dln = runInit()
        assertNull(dln.getDeferredDeepLink())
        verify(exactly = 3) { client.startConnection(any()) }
    }

    @Test
    fun `AND-13 service disconnect after the referrer was delivered does not crash and is ignored`() {
        mockInstallReferrer(
            referrer = "fp_id=$FP_ID",
            afterSetup = { listener -> listener.onInstallReferrerServiceDisconnected() },
        )
        try {
            val dln = runInit()
            assertEquals(DEEPLINK_ID, dln.getDeferredDeepLink()!!.id)
        } catch (e: IllegalStateException) {
            fail("second resume crashed: $e")
        }
    }

    @Test
    fun `AND-08 AND-13 startConnection throwing SecurityException does not escape`() {
        mockInstallReferrer(startConnectionThrows = SecurityException("Not allowed to bind to service"))
        try {
            assertNull(runInit().getDeferredDeepLink())
        } catch (e: SecurityException) {
            fail("referrer processing threw: $e")
        }
    }

    @Test
    fun `AND-12 the referrer is processed by init so the first launch does not race`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        server.dispatcher = routingDispatcher(
            mapOf(
                "/api/v1/sdk/init" to { json(initResponse()) },
                "/api/v1/sdk/referrer-lookup" to { json(referrerLookupSuccess()).setHeadersDelay(200, TimeUnit.MILLISECONDS) },
            )
        )
        val dln = newDln(context)
        redirectTo(dln, server)
        DLNTestSupport.setSingleton(dln)
        dln.start()
        // checkDeferredDeepLink awaits the in-flight referrer processing instead of racing it
        val link = runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow()!!
        assertEquals(DeferredDeepLinkSource.REFERRER, link.source)
        assertEquals(1, lookupRequests().size)
    }

    // ---- Response mapping (AND-19) ----

    @Test
    fun `successful lookup maps the server deeplink`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = runInit()

        val link = dln.getDeferredDeepLink()!!
        assertEquals(DEEPLINK_ID, link.id)
        assertEquals("promo?code=SAVE20", link.targetUrl)
        assertEquals(DLNTestSupport.CAMPAIGN_ID, link.campaignId)
        assertEquals("facebook", link.metadata["source"])
        assertEquals("2026-10-18T10:35:00.000Z", link.matchedAt)
        assertEquals("2026-10-18T11:35:00.000Z", link.expiresAt)
        val data = dln.getReferrerData()!!
        assertEquals("fp_id=$FP_ID", data.referrerString)
        assertEquals(FP_ID, data.fpId)
    }

    @Test
    fun `campaign_id omitted by the server maps to null`() {
        lookupResponse = { json(referrerLookupSuccess(deeplinkJson(campaignId = null))) }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        assertNull(runInit().getDeferredDeepLink()!!.campaignId)
    }

    @Test
    fun `AND-19 query_params and full_path from referrer-lookup are decoded`() {
        lookupResponse = {
            json(referrerLookupSuccess(deeplinkJson(
                queryParams = """{ "code": "SAVE20", "tag": ["a","b"], "flag": true }""",
                fullPath = "/promo?code=SAVE20&tag=a&tag=b&flag",
            )))
        }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val link = runInit().getDeferredDeepLink()!!
        assertEquals("/promo?code=SAVE20&tag=a&tag=b&flag", link.fullPath)
        assertEquals(QueryValue.Multiple(listOf("a", "b")), link.typedQueryParams["tag"])
        assertEquals(QueryValue.Flag, link.typedQueryParams["flag"])
    }

    @Test
    fun `AND-19 SDK-51 deeplink metadata null or non-object does not crash or leak null`() {
        for (metadata in listOf("null", "\"oops\"", "[1,2]")) {
            DLNTestSupport.resetStatics()
            lookupResponse = { json(referrerLookupSuccess(deeplinkJson(metadata = metadata))) }
            unmockkAll()
            mockInstallReferrer(referrer = "fp_id=$FP_ID")
            val dln = runInit()
            val link = dln.getDeferredDeepLink()!!
            @Suppress("SENSELESS_COMPARISON")
            assertTrue("metadata is null in a non-null field", link.metadata != null)
            dln.resetLocalStateForTesting()
        }
    }

    @Test
    fun `FLOW-23 AND-17 lookup 404 is definitive - no link, cached, and does not throw`() {
        lookupResponse = { json("""{"success":false,"message":"Fingerprint not found"}""", 404) }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = runInit()
        assertNull(dln.getDeferredDeepLink())
        assertEquals(FP_ID, dln.getReferrerData()!!.fpId)
        assertTrue(dln.referrerOutcomeIsDefinitive())
    }

    @Test
    fun `AND-17 lookup 410 and 400 are definitive and cached`() {
        for (code in listOf(410, 400)) {
            DLNTestSupport.resetStatics()
            unmockkAll()
            lookupResponse = { json("""{"success":false,"message":"x"}""", code) }
            mockInstallReferrer(referrer = "fp_id=$FP_ID")
            val dln = runInit()
            assertNull(dln.getDeferredDeepLink())
            assertTrue("status $code", dln.referrerOutcomeIsDefinitive())
            dln.resetLocalStateForTesting()
        }
    }

    @Test
    fun `200 with success false yields null`() {
        lookupResponse = { json("""{"success":false,"message":"Fingerprint has expired"}""") }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        assertNull(runInit().getDeferredDeepLink())
    }

    @Test
    fun `AND-17 malformed lookup JSON yields null without throwing and is not cached`() {
        lookupResponse = { MockResponse().setResponseCode(200).setBody("<html>oops</html>") }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = runInit()
        assertNull(dln.getDeferredDeepLink())
        assertTrue(!dln.referrerOutcomeIsDefinitive())
    }

    // ---- Caching (AND-17) ----

    @Test
    fun `AND-17 successful lookup is cached and not re-fetched on the next launch`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        runInit()
        assertEquals(1, lookupRequests().size)

        unmockkAll()
        val client = mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val second = runInit()
        assertTrue(lookupRequests().isEmpty())
        verify(exactly = 0) { client.startConnection(any()) }
        assertEquals(DEEPLINK_ID, second.getDeferredDeepLink()!!.id)
    }

    @Test
    fun `FLOW-23 AND-17 transient lookup failures (network, 401, 403, 429, 5xx) are retried on the next launch`() {
        val transient = listOf(
            { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) },
            { json("""{"error":"Invalid API key"}""", 401) },
            { json("""{"error":"Account is not active"}""", 403) },
            { json("""{"error":"Rate limit exceeded"}""", 429) },
            { json("""{"success":false,"message":"Internal server error"}""", 500) },
        )
        for (failure in transient) {
            DLNTestSupport.resetStatics()
            unmockkAll()
            lookupResponse = failure
            mockInstallReferrer(referrer = "fp_id=$FP_ID")
            val first = runInit()
            assertNull(first.getDeferredDeepLink())
            assertNull(first.getReferrerData())

            lookupResponse = { json(referrerLookupSuccess()) }
            unmockkAll()
            mockInstallReferrer(referrer = "fp_id=$FP_ID")
            val second = runInit()
            assertNotNull("deferred link lost after a transient failure", second.getDeferredDeepLink())
            second.resetLocalStateForTesting()
        }
    }

    @Test
    fun `AND-17 a transient failure is retried by the next checkDeferredDeepLink call in the same launch`() {
        var fail = true
        lookupResponse = { if (fail) json("{}", 503) else json(referrerLookupSuccess()) }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = runInit()
        DLNTestSupport.setSingleton(dln)
        assertNull(runBlocking { DLN.checkDeferredDeepLink() }.getOrNull())
        fail = false
        val link = runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow()
        assertEquals(DeferredDeepLinkSource.REFERRER, link!!.source)
    }

    @Test
    fun `AND-18 getDeferredDeepLink keeps returning the cached referrer result on every launch`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = runInit()
        DLNTestSupport.setSingleton(dln)
        assertNotNull(runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow())
        assertNull(runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow())
        assertEquals(DEEPLINK_ID, dln.getDeferredDeepLink()!!.id)
        assertEquals(DEEPLINK_ID, runInit().getDeferredDeepLink()!!.id)
    }
}
