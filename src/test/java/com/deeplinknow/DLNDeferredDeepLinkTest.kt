package com.deeplinknow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.deeplinknow.DLNTestSupport.DEEPLINK_ID
import com.deeplinknow.DLNTestSupport.FP_ID
import com.deeplinknow.DLNTestSupport.deeplinkJson
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.matchJson
import com.deeplinknow.DLNTestSupport.matchResponse
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.redirectTo
import com.deeplinknow.DLNTestSupport.referrerLookupSuccess
import com.deeplinknow.DLNTestSupport.routingDispatcher
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one call: DLN.checkDeferredDeepLink (docs/sdks/overview.md SDK-10..27, docs/sdks/android.md
 * AND-12, AND-17, AND-18, AND-22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNDeferredDeepLinkTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var lookup: () -> MockResponse = { json(referrerLookupSuccess()) }
    private var matchBody: () -> MockResponse = { json(matchResponse()) }
    private var init: () -> MockResponse = { json(initResponse()) }
    private val matchCalls = AtomicInteger()
    private val lookupCalls = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        server = MockWebServer().apply {
            dispatcher = routingDispatcher(
                mapOf(
                    "/api/v1/sdk/init" to { init() },
                    "/api/v1/sdk/referrer-lookup" to { lookupCalls.incrementAndGet(); lookup() },
                    "/api/v1/sdk/match" to { matchCalls.incrementAndGet(); matchBody() },
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

    /** Fresh process: new instance registered as the singleton, init + referrer finished. */
    private fun launch(retryAttempts: Int = 0): DLN {
        val dln = newDln(context, retryAttempts = retryAttempts)
        redirectTo(dln, server)
        DLNTestSupport.setSingleton(dln)
        runBlocking { dln.startAndAwait() }
        return dln
    }

    private fun check(minConfidence: Double? = null, force: Boolean = false) =
        runBlocking { DLN.checkDeferredDeepLink(minConfidence = minConfidence, force = force) }

    private fun noReferrer() = mockInstallReferrer(referrer = "utm_source=google-play&utm_medium=organic")

    // ---- Chain (SDK-10, SDK-11) ----

    @Test
    fun `SDK-10 SDK-11 referrer hit returns the link and does not call match`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        val link = check().getOrThrow()!!
        assertEquals(DeferredDeepLinkSource.REFERRER, link.source)
        assertEquals("/promo", link.path)
        assertEquals(0, matchCalls.get())
    }

    @Test
    fun `SDK-11 no referrer falls through to fingerprint`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson(62.0, "MEDIUM"))) }
        launch()
        val link = check().getOrThrow()!!
        assertEquals(DeferredDeepLinkSource.FINGERPRINT, link.source)
        assertEquals(1, matchCalls.get())
    }

    @Test
    fun `SDK-11 AND-17 a cached referrer none (404) still runs the fingerprint step`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        lookup = { json("""{"success":false,"message":"Fingerprint not found"}""", 404) }
        matchBody = { json(matchResponse(matchJson())) }
        launch()
        assertEquals(DeferredDeepLinkSource.FINGERPRINT, check().getOrThrow()!!.source)
    }

    @Test
    fun `SDK-11 a transient referrer failure continues to fingerprint in this call`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        lookup = { json("{}", 503) }
        matchBody = { json(matchResponse(matchJson())) }
        launch()
        assertEquals(DeferredDeepLinkSource.FINGERPRINT, check().getOrThrow()!!.source)
    }

    @Test
    fun `SDK-11 the Android chain never reads the clipboard`() {
        noReferrer()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dln", "https://myapp.deeplinknow.com/clip"))
        val dln = launch()
        assertNull(check().getOrThrow())
        assertFalse(dln.clipboardRead)
    }

    // ---- Fingerprint step (SDK-13, SDK-14) ----

    @Test
    fun `SDK-13 the first candidate in server order with a deeplink wins, no re-scoring`() {
        noReferrer()
        matchBody = {
            json(matchResponse(
                matchJson(40.0, "LOW", deeplink = null),
                matchJson(35.0, "LOW", deeplinkJson(id = "00000000-0000-4000-8000-000000000002")),
                matchJson(90.0, "HIGH", deeplinkJson(id = "00000000-0000-4000-8000-000000000003")),
            ))
        }
        launch()
        val link = check().getOrThrow()!!
        assertEquals("00000000-0000-4000-8000-000000000002", link.deeplinkId)
        assertEquals(35.0, link.confidenceScore, 0.0)
        assertEquals(MatchQuality.LOW, link.matchQuality)
    }

    @Test
    fun `SDK-14 minConfidence is off by default and filters only fingerprint candidates when set`() {
        noReferrer()
        matchBody = {
            json(matchResponse(
                matchJson(45.0, "LOW", deeplinkJson(id = "00000000-0000-4000-8000-000000000001")),
                matchJson(80.0, "HIGH", deeplinkJson(id = "00000000-0000-4000-8000-000000000002")),
            ))
        }
        launch()
        assertEquals("00000000-0000-4000-8000-000000000002", check(minConfidence = 50.0, force = true).getOrThrow()!!.deeplinkId)
        assertNull(check(minConfidence = 81.0, force = true).getOrThrow())
        assertEquals("00000000-0000-4000-8000-000000000001", check(force = true).getOrThrow()!!.deeplinkId)
    }

    @Test
    fun `SDK-14 minConfidence never filters a referrer result`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        assertEquals(DeferredDeepLinkSource.REFERRER, check(minConfidence = 101.0).getOrThrow()!!.source)
    }

    // ---- Result shape (SDK-15..19) ----

    @Test
    fun `SDK-15 SDK-16 SDK-17 SDK-19 referrer result shape`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        lookup = {
            json(referrerLookupSuccess(deeplinkJson(
                targetUrl = "promo?code=SAVE20&tag=a&tag=b",
                queryParams = """{ "code": "SAVE20", "tag": ["a","b"] }""",
                fullPath = "/promo?code=SAVE20&tag=a&tag=b",
            )))
        }
        launch()
        val link = check().getOrThrow()!!
        assertEquals("/promo", link.path)
        assertEquals("/promo?code=SAVE20&tag=a&tag=b", link.fullPath)
        assertEquals(
            linkedMapOf("code" to QueryValue.Single("SAVE20"), "tag" to QueryValue.Multiple(listOf("a", "b"))),
            link.queryParams,
        )
        assertEquals(100.0, link.confidenceScore, 0.0)
        assertEquals(MatchQuality.HIGH, link.matchQuality)
        assertEquals(DEEPLINK_ID, link.deeplinkId)
        assertEquals("promo?code=SAVE20&tag=a&tag=b", link.targetUrl)
        assertEquals(DLNTestSupport.CAMPAIGN_ID, link.campaignId)
        assertEquals("facebook", link.metadata!!["source"])
        assertEquals("2026-10-18T10:35:00.000Z", link.matchedAt)
        assertEquals("2026-10-18T11:35:00.000Z", link.expiresAt)
        assertNull(link.matchDetails)
    }

    @Test
    fun `SDK-16 SDK-19 fingerprint result carries the server score, quality and match details unchanged`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson(62.0, "MEDIUM"))) }
        launch()
        val link = check().getOrThrow()!!
        assertEquals(62.0, link.confidenceScore, 0.0)
        assertEquals(MatchQuality.MEDIUM, link.matchQuality)
        assertEquals(40.0, link.matchDetails!!.ipMatch.score, 0.0)
    }

    @Test
    fun `SDK-16 match_quality missing from an older server is derived from the thresholds`() {
        noReferrer()
        for ((score, quality) in listOf(75.0 to MatchQuality.HIGH, 74.9 to MatchQuality.MEDIUM, 50.0 to MatchQuality.MEDIUM, 49.0 to MatchQuality.LOW, 30.0 to MatchQuality.LOW)) {
            matchBody = { json(matchResponse(matchJson(score, quality = null))) }
            DLNTestSupport.resetStatics()
            launch()
            assertEquals("score $score", quality, check(force = true).getOrThrow()!!.matchQuality)
        }
        assertEquals(MatchQuality.HIGH, MatchQuality.fromScore(99.0))
    }

    @Test
    fun `SDK-17 older server without full_path or query_params - fullPath falls back to target_url with a leading slash, params parsed`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson(deeplink = deeplinkJson(targetUrl = "/shoes?size=9&c=red+blue", includeContext = false)))) }
        launch()
        val link = check().getOrThrow()!!
        assertEquals("/shoes?size=9&c=red+blue", link.fullPath)
        assertEquals("/shoes", link.path)
        assertEquals(QueryValue.Single("red blue"), link.queryParams["c"])

        // SDK-17: a target_url without a leading slash gets one
        matchBody = { json(matchResponse(matchJson(deeplink = deeplinkJson(targetUrl = "promo?code=X", includeContext = false)))) }
        val relative = check(force = true).getOrThrow()!!
        assertEquals("/promo?code=X", relative.fullPath)
        assertEquals("/promo", relative.path)
        assertEquals(QueryValue.Single("X"), relative.queryParams["code"])

        // SDK-17: "/" only when target_url is also absent
        matchBody = { json(matchResponse(matchJson(deeplink = """{ "id": "${DEEPLINK_ID}", "metadata": {} }"""))) }
        assertEquals("/", check(force = true).getOrThrow()!!.fullPath)
    }

    @Test
    fun `SDK-17 full_path wins over target_url and query_params pass through unchanged`() {
        noReferrer()
        matchBody = {
            json(matchResponse(matchJson(deeplink = deeplinkJson(
                targetUrl = "canonical?a=1&b=2",
                queryParams = """{ "b": "2", "a": "1" }""",
                fullPath = "/raw?b=2&a=1",
            ))))
        }
        launch()
        val link = check().getOrThrow()!!
        assertEquals("/raw?b=2&a=1", link.fullPath)
        assertEquals(listOf("b", "a"), link.queryParams.keys.toList())
    }

    @Test
    fun `SDK-18 url is built on the primary domain unless target_url is an absolute link-domain URL`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson())) }
        launch()
        // primary domain = first verified custom domain
        assertEquals("https://links.myapp.com/promo?code=SAVE20", check().getOrThrow()!!.url)

        matchBody = { json(matchResponse(matchJson(deeplink = deeplinkJson(targetUrl = "https://myapp.deeplinknow.com/abs?x=1")))) }
        assertEquals("https://myapp.deeplinknow.com/abs?x=1", check(force = true).getOrThrow()!!.url)

        matchBody = { json(matchResponse(matchJson(deeplink = deeplinkJson(targetUrl = "https://example.org/page")))) }
        assertEquals("https://links.myapp.com/promo?code=SAVE20", check(force = true).getOrThrow()!!.url)
    }

    @Test
    fun `SDK-18 without a verified custom domain the alias subdomain is the primary domain`() {
        init = { json(initResponse(customDomain = null, verified = false)) }
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        assertEquals("https://myapp.deeplinknow.com/promo?code=SAVE20", check().getOrThrow()!!.url)
    }

    // ---- First launch (SDK-23..27, AND-18, AND-22) ----

    @Test
    fun `SDK-23 AND-18 a delivered link is returned only once, later calls make no requests`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        assertNotNull(check().getOrThrow())
        val before = server.requestCount
        assertNull(check().getOrThrow())
        assertEquals(before, server.requestCount)
        // and on the next launch
        launch()
        assertNull(check().getOrThrow())
        assertEquals(0, matchCalls.get())
    }

    @Test
    fun `SDK-23 definitive nothing sets the flag too`() {
        noReferrer()
        launch()
        assertNull(check().getOrThrow())
        assertEquals(1, matchCalls.get())
        assertNull(check().getOrThrow())
        assertEquals(1, matchCalls.get())
    }

    @Test
    fun `SDK-23 force ignores the first-launch flag`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        assertNotNull(check().getOrThrow())
        assertEquals(DeferredDeepLinkSource.REFERRER, check(force = true).getOrThrow()!!.source)
    }

    @Test
    fun `SDK-24 SDK-54 errors do not set the flag and the next call tries again`() {
        noReferrer()
        matchBody = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        launch()
        val error = check().exceptionOrNull() as DLNException
        assertEquals(DLNErrorCode.NETWORK, error.code)
        for (status in listOf(429, 500)) {
            matchBody = { json("""{"error":"x"}""", status) }
            assertTrue(check().isFailure)
        }
        matchBody = { json("<html>", 200) }
        assertEquals(DLNErrorCode.INVALID_RESPONSE, (check().exceptionOrNull() as DLNException).code)
        matchBody = { json(matchResponse(matchJson())) }
        assertNotNull(check().getOrThrow())
    }

    @Test
    fun `SDK-24 a transient referrer failure with no fingerprint match does not set the flag`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        lookup = { json("{}", 500) }
        launch()
        assertNull(check().getOrThrow())
        lookup = { json(referrerLookupSuccess()) }
        assertEquals(DeferredDeepLinkSource.REFERRER, check().getOrThrow()!!.source)
    }

    @Test
    fun `SDK-26 resetDeferredDeepLink clears the flag and the referrer cache in memory and on disk`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = launch()
        assertNotNull(check().getOrThrow())
        DLN.resetDeferredDeepLink()
        assertNull(dln.getReferrerData())
        assertNull(dln.getDeferredDeepLink())
        val lookupsBefore = lookupCalls.get()
        assertEquals(DeferredDeepLinkSource.REFERRER, check().getOrThrow()!!.source)
        assertEquals(lookupsBefore + 1, lookupCalls.get())
    }

    @Test
    fun `SDK-25 AND-22 first-launch state lives in noBackupFilesDir, not in backed-up SharedPreferences`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        val dln = launch()
        assertNotNull(check().getOrThrow())
        val file = dln.stateFile
        assertTrue(file.exists())
        assertTrue(file.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        assertFalse(File(prefsDir, "dln_referrer.xml").exists())
    }

    @Test
    fun `SDK-25 legacy dln_referrer prefs from an older SDK are migrated as already-checked and deleted`() {
        val prefs = context.getSharedPreferences("dln_referrer", Context.MODE_PRIVATE)
        prefs.edit().putString("referrer_data", """{"referrerString":"fp_id=$FP_ID","fpId":"$FP_ID","processedAt":1}""").commit()
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        assertNull(check().getOrThrow())
        assertEquals(0, lookupCalls.get())
        assertFalse(context.getSharedPreferences("dln_referrer", Context.MODE_PRIVATE).contains("referrer_data"))
    }

    @Test
    fun `SDK-27 concurrent calls do the work once and get the same result`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson())).setHeadersDelay(200, TimeUnit.MILLISECONDS) }
        launch()
        val results = runBlocking {
            (1..5).map { async { DLN.checkDeferredDeepLink() } }.awaitAll()
        }
        assertEquals(1, matchCalls.get())
        val first = results.first().getOrThrow()
        assertNotNull(first)
        assertTrue(results.all { it.getOrThrow() == first })
    }

    // ---- Callback variants (SDK-56) ----

    @Test
    fun `SDK-56 the callback variant calls back once on the main thread`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        launch()
        val got = mutableListOf<Result<DeferredDeepLink?>>()
        var onMain = false
        DLN.checkDeferredDeepLink { result ->
            onMain = Looper.myLooper() == Looper.getMainLooper()
            got += result
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(1, got.size)
        assertTrue(onMain)
        assertEquals(DeferredDeepLinkSource.REFERRER, got[0].getOrThrow()!!.source)
    }

    @Test
    fun `SDK-56 the Java callback variant reports errors as DLNException`() {
        var error: DLNException? = null
        var called = false
        DLN.checkDeferredDeepLink(null, false, object : DeferredDeepLinkCallback {
            override fun onResult(link: DeferredDeepLink?, error_: DLNException?) {
                called = true
                error = error_
            }
        })
        val deadline = System.currentTimeMillis() + 5_000
        while (!called && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(called)
        assertEquals(DLNErrorCode.NOT_INITIALIZED, error!!.code)
    }

    // ---- Logging (SDK-72) ----

    @Test
    fun `SDK-72 with enableLogs each step logs its outcome`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson(62.0, "MEDIUM"))) }
        launch()
        ShadowLog.clear()
        check()
        val text = ShadowLog.getLogs().filter { it.tag == "DLN" }.joinToString("\n") { it.msg }
        assertTrue(text, text.contains("referrer"))
        assertTrue(text, text.contains("fingerprint"))
        assertTrue(text, text.contains("62"))
    }

    // ---- Privacy (SDK-65) ----

    @Test
    fun `SDK-65 nothing but the first-launch flag and the referrer result is persisted`() {
        noReferrer()
        matchBody = { json(matchResponse(matchJson())) }
        val dln = launch()
        check()
        val stored = DLNTestSupport.parseObject(dln.stateFile.readText())
        assertTrue(stored.keySet().toString(), stored.keySet().all { it in setOf("deferred_checked", "referrer_data", "referrer_none", "processed_at") })
        assertFalse(dln.stateFile.readText().contains(DLNTestSupport.TEST_API_KEY))
        assertFalse(dln.stateFile.readText().contains("user_agent"))
    }
}
