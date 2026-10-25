package com.deeplinknow

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.deeplinknow.DLNTestSupport.matchJson
import com.deeplinknow.DLNTestSupport.DEEPLINK_ID
import com.deeplinknow.DLNTestSupport.FP_ID
import com.deeplinknow.DLNTestSupport.deeplinkJson
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.referrerLookupSuccess
import com.deeplinknow.DLNTestSupport.routingDispatcher
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One link object for every open (1.4.0): docs/sdks/overview.md SDK-86..92 and
 * docs/sdks/android.md AND-35..40. Java source compatibility is in DLNJavaApiTest.java.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNLinkTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var lookup: () -> MockResponse = { json(referrerLookupSuccess()) }
    private var init: () -> MockResponse = { json(initResponse()) }
    private var resolve: () -> MockResponse = { json("""{"error":"Deeplink not found"}""", 404) }
    private var match: () -> MockResponse = { json(DLNTestSupport.matchResponse()) }
    private val lookupCalls = AtomicInteger()
    private val matchCalls = AtomicInteger()
    private val resolveRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val received = CopyOnWriteArrayList<DLNLink>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                val routes = routingDispatcher(
                    mapOf(
                        "/api/v1/sdk/init" to { init() },
                        "/api/v1/sdk/referrer-lookup" to { lookupCalls.incrementAndGet(); lookup() },
                        "/api/v1/sdk/match" to { matchCalls.incrementAndGet(); match() },
                        "/api/v1/sdk/resolve" to { resolve() },
                    )
                )

                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath == "/api/v1/sdk/resolve") resolveRequests += request
                    return routes.dispatch(request)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    // ---- helpers ----

    private fun baseUrl() = server.url("/").toString()

    private fun noReferrer() = mockInstallReferrer(referrer = "utm_source=google-play&utm_medium=organic")

    /** DLN.init against the mock server, waiting for init (timeout 10 s unless given). */
    private fun initSdk(apiKey: String = DLNTestSupport.TEST_API_KEY, timeoutMs: Long = 10_000, wait: Boolean = true) {
        DLN.init(context, apiKey, baseUrl = baseUrl(), retryAttempts = 0, timeoutMs = timeoutMs)
        if (wait) runBlocking { DLN.awaitInitialization() }
    }

    private fun record(options: DLNLinkOptions = DLNLinkOptions()): DLNLinkSubscription =
        DLN.onLink(options) { received += it }

    private fun idleMainUntil(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return true
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return done()
    }

    /** Lets in-flight work finish and the main looper drain, for "nothing else arrives" checks. */
    private fun settle(ms: Long = 300) {
        idleMainUntil(ms) { false }
    }

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    // ---- SDK-86: deferred delivery through onLink ----

    @Test
    fun `SDK-86 onLink starts the deferred check and delivers the referrer link as a deferred DLNLink`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        val link = received.single()
        assertEquals(DLNLinkType.DEFERRED, link.type)
        assertEquals(DLNLinkSource.REFERRER, link.source)
        assertEquals("referrer", link.source.value)
        assertEquals("deferred", link.type.value)
        assertEquals("/promo", link.path)
        assertEquals("/promo?code=SAVE20", link.fullPath)
        assertEquals(QueryValue.Single("SAVE20"), link.queryParams["code"])
        assertEquals(100.0, link.confidenceScore, 0.0)
        assertEquals(MatchQuality.HIGH, link.matchQuality)
        assertEquals(DEEPLINK_ID, link.deeplinkId)
        assertEquals(DLNTestSupport.CAMPAIGN_ID, link.campaignId)
        assertEquals("facebook", link.metadata?.get("source"))
        assertEquals("https://links.myapp.com/promo?code=SAVE20", link.url)
        assertEquals(1, lookupCalls.get())
    }

    @Test
    fun `SDK-86 DLNLink fromDeferred keeps every DeferredDeepLink field`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        val deferred = runBlocking { DLN.checkDeferredDeepLink(force = true) }.getOrThrow()!!
        val link = DLNLink.fromDeferred(deferred)
        assertEquals(deferred.path, link.path)
        assertEquals(deferred.queryParams, link.queryParams)
        assertEquals(deferred.fullPath, link.fullPath)
        assertEquals(deferred.url, link.url)
        assertEquals(deferred.source.value, link.source.value)
        assertEquals(deferred.confidenceScore, link.confidenceScore, 0.0)
        assertEquals(deferred.matchQuality, link.matchQuality)
        assertEquals(deferred.deeplinkId, link.deeplinkId)
        assertEquals(deferred.targetUrl, link.targetUrl)
        assertEquals(deferred.campaignId, link.campaignId)
        assertEquals(deferred.metadata, link.metadata)
        assertEquals(deferred.matchedAt, link.matchedAt)
        assertEquals(deferred.expiresAt, link.expiresAt)
        assertEquals(deferred.matchDetails, link.matchDetails)
        assertEquals(DLNLinkType.DEFERRED, link.type)
        for (source in DeferredDeepLinkSource.values()) {
            assertEquals(source.value, DLNLinkSource.from(source).value)
        }
    }

    @Test
    fun `SDK-86 AND-39 a handler registered before init starts the check once init is called`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        record()
        settle(100)
        assertEquals(0, lookupCalls.get())
        initSdk()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(DLNLinkType.DEFERRED, received.single().type)
    }

    @Test
    fun `SDK-86 SDK-23 the deferred check runs once per process however often onLink is called`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        record()
        record()
        settle()
        assertEquals(1, received.size)
        assertEquals(1, lookupCalls.get())
    }

    @Test
    fun `SDK-86 a handler registered after the link arrived gets it once (late handler replay)`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        val first = DLN.onLink { }
        first.remove() // the check is running, but nobody is listening when the link arrives
        settle()
        assertEquals(1, lookupCalls.get())
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(DLNLinkType.DEFERRED, received.single().type)
        record()
        settle()
        assertEquals(1, received.size)
    }

    @Test
    fun `SDK-86 a direct open before any handler is replayed to the first handler`() {
        noReferrer()
        initSdk()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/early?x=1")))
        settle(100)
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals("/early", received.single().path)
        assertEquals(DLNLinkType.DIRECT, received.single().type)
    }

    @Test
    fun `SDK-88 a deferred link and a direct open on the same first launch are both delivered, deferred first`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        // start the check, then leave no handler while the deferred and the direct link arrive
        DLN.onLink { }.remove()
        settle()
        assertEquals(1, lookupCalls.get())
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/direct")))
        record()
        assertTrue(idleMainUntil { received.size == 2 })
        assertEquals(DLNLinkType.DEFERRED, received[0].type)
        assertEquals(DLNLinkType.DIRECT, received[1].type)
    }

    @Test
    fun `SDK-85 a failed deferred check delivers nothing, sets lastError and never throws`() {
        mockInstallReferrer(responseCode = InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
        init = { json("""{"error":"Invalid API key"}""", 401) }
        initSdk()
        record()
        settle()
        assertTrue(received.isEmpty())
        assertEquals(DLNErrorCode.NOT_INITIALIZED, DLN.lastError?.code)
    }

    // ---- SDK-87: direct opens ----

    @Test
    fun `SDK-87 AND-35 handleIntent delivers an App Link as a direct appLink DLNLink`() {
        noReferrer()
        initSdk()
        record()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/products/123?ref=news&tag=a&tag=b")))
        assertTrue(idleMainUntil { received.size == 1 })
        val link = received.single()
        assertEquals(DLNLinkType.DIRECT, link.type)
        assertEquals(DLNLinkSource.APP_LINK, link.source)
        assertEquals("appLink", link.source.value)
        assertEquals("/products/123", link.path)
        assertEquals("/products/123?ref=news&tag=a&tag=b", link.fullPath)
        assertEquals(QueryValue.Multiple(listOf("a", "b")), link.queryParams["tag"])
        assertEquals("https://myapp.deeplinknow.com/products/123?ref=news&tag=a&tag=b", link.url)
        assertEquals(100.0, link.confidenceScore, 0.0)
        assertEquals(MatchQuality.HIGH, link.matchQuality)
        assertNull(link.deeplinkId)
        assertNull(link.targetUrl)
        assertNull(link.metadata)
    }

    @Test
    fun `SDK-87 SDK-31 handleIntent accepts the verified custom domain and rejects other hosts`() {
        noReferrer()
        initSdk()
        record()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://links.myapp.com/invite/8Fk2")))
        assertFalse(DLN.handleIntent(viewIntent("https://otherapp.deeplinknow.com/x")))
        assertFalse(DLN.handleIntent(viewIntent("https://evil.com/?u=myapp.deeplinknow.com")))
        assertFalse(DLN.handleIntent(viewIntent("https://myapp.deeplink.now/x")))
        assertFalse(DLN.handleIntent(Intent(Intent.ACTION_MAIN)))
        assertFalse(DLN.handleIntent(null))
        assertTrue(idleMainUntil { received.size == 1 })
        settle(100)
        assertEquals(listOf("/invite/8Fk2"), received.map { it.path })
    }

    @Test
    fun `SDK-87 AND-35 an App Link handed over while init is in flight is decided and delivered after init`() {
        noReferrer()
        init = { json(initResponse()).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        record()
        initSdk(wait = false)
        assertFalse(DLN.isInitialized)
        // a custom domain is only known after init
        assertTrue(DLN.handleIntent(viewIntent("https://links.myapp.com/late")))
        assertTrue(DLN.handleIntent(viewIntent("https://otherapp.deeplinknow.com/nope")))
        assertTrue(idleMainUntil { received.isNotEmpty() })
        settle()
        assertEquals(listOf("/late"), received.map { it.path })
    }

    @Test
    fun `SDK-87 AND-36 the app's custom scheme is a direct scheme link with the host as the first path segment`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(schemes = setOf("myapp")))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("myapp://products/123?x=1#dln=abc")))
        assertTrue(idleMainUntil { received.size == 1 })
        val link = received.single()
        assertEquals(DLNLinkType.DIRECT, link.type)
        assertEquals(DLNLinkSource.SCHEME, link.source)
        assertEquals("scheme", link.source.value)
        assertEquals("/products/123", link.path)
        assertEquals("/products/123?x=1", link.fullPath)
        assertEquals(QueryValue.Single("1"), link.queryParams["x"])
        assertEquals("myapp://products/123?x=1", link.url)

        // another scheme, or a system one, is not the app's
        assertFalse(DLN.handleIntent(viewIntent("otherapp://products/123")))
        assertFalse(DLN.handleIntent(viewIntent("mailto:a@b.c")))
        assertFalse(DLN.handleIntent(viewIntent("content://x/y")))
    }

    @Test
    fun `AND-36 parseScheme maps scheme URLs to paths`() {
        assertEquals("/products/123", DLNLinks.parseScheme("myapp:///products/123")!!.path)
        assertEquals("/", DLNLinks.parseScheme("myapp://")!!.path)
        assertEquals("/home", DLNLinks.parseScheme("myapp://home")!!.fullPath)
        assertNull(DLNLinks.parseScheme("myapp:opaque"))
        assertNull(DLNLinks.parseScheme("https://myapp.deeplinknow.com/x"))
        assertNull(DLNLinks.parseScheme(""))
    }

    @Test
    fun `AND-36 without configured schemes, a scheme the app's manifest declares is accepted`() {
        noReferrer()
        initSdk()
        val component = ComponentName(context.packageName, "com.example.MainActivity")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("declaredapp")
            },
        )
        record()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("declaredapp://open/me")))
        assertFalse(DLN.handleIntent(viewIntent("undeclared://open/me")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(DLNLinkSource.SCHEME, received.single().source)
        assertEquals("/open/me", received.single().path)
    }

    @Test
    fun `SDK-91 AND-40 handleIntent and handleDeepLink stay separate - the legacy callback is unchanged`() {
        noReferrer()
        initSdk()
        record()
        settle()
        val legacy = mutableListOf<DeepLinkData>()
        DLN.setDeepLinkCallback(object : DeepLinkCallback {
            override fun onDeepLinkOpen(deepLinkData: DeepLinkData) {
                legacy += deepLinkData
            }
        })
        assertTrue(DLN.handleDeepLink("https://myapp.deeplinknow.com/old?code=1"))
        assertEquals(1, legacy.size)
        assertEquals("old", legacy[0].route)
        assertEquals(mapOf("code" to "1"), legacy[0].params)
        settle(100)
        assertTrue(received.isEmpty())

        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/new")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(1, legacy.size)
    }

    @Test
    fun `SDK-85 handleIntent never throws on odd intents`() {
        initSdk()
        record()
        assertFalse(DLN.handleIntent(Intent()))
        assertFalse(DLN.handleIntent(Intent(Intent.ACTION_VIEW)))
        assertFalse(DLN.handleIntent(viewIntent("not a url")))
        assertFalse(DLN.handleIntent(viewIntent("https://a.deeplinknow.com@evil.com/")))
        assertFalse(DLN.handleIntent(viewIntent("   ")))
    }

    @Test
    fun `SDK-87 SDK-37 before init is ever called, handleIntent uses the provisional host rule`() {
        record()
        assertTrue(DLN.handleIntent(viewIntent("https://someapp.deeplinknow.com/pre")))
        assertFalse(DLN.handleIntent(viewIntent("https://links.myapp.com/pre")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals("/pre", received.single().path)
    }

    // ---- SDK-88: dedupe ----

    @Test
    fun `SDK-88 AND-37 the same intent from onCreate and onNewIntent is delivered once`() {
        noReferrer()
        initSdk()
        record()
        settle()
        val intent = viewIntent("https://myapp.deeplinknow.com/once")
        assertTrue(DLN.handleIntent(intent)) // onCreate
        assertTrue(DLN.handleIntent(intent)) // onNewIntent with the same intent
        assertTrue(DLN.handleIntent(Intent(intent))) // a copy carries the handled mark
        assertTrue(intent.getBooleanExtra(DLNLinkRouter.EXTRA_HANDLED, false))
        assertTrue(idleMainUntil { received.size == 1 })
        settle(200)
        assertEquals(1, received.size)
    }

    @Test
    fun `SDK-88 AND-37 a new intent with the same URL inside the window is one open, after it a new open`() {
        noReferrer()
        initSdk()
        record()
        settle()
        var now = 10_000L
        DLNLinkRouter.clock = { now }
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/again")))
        now += 2_900 // SDK-88: within 3 seconds of the last open, still the same open
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/again")))
        assertTrue(idleMainUntil { received.size == 1 })
        settle(100)
        assertEquals(1, received.size)
        now += 3_000
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/again")))
        assertTrue(idleMainUntil { received.size == 2 })
        // a different URL is never a duplicate
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/other")))
        assertTrue(idleMainUntil { received.size == 3 })
    }

    @Test
    fun `SDK-88 AND-37 an intent relaunched from Recents is not delivered again`() {
        noReferrer()
        initSdk()
        record()
        settle()
        val intent = viewIntent("https://myapp.deeplinknow.com/history")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertTrue(DLN.handleIntent(intent))
        settle()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `SDK-88 AND-41 an activity re-created from saved state does not deliver its old intent again`() {
        noReferrer()
        initSdk()
        record()
        settle()
        // A fresh launch delivers the open.
        val first = Robolectric.buildActivity(Activity::class.java, viewIntent("https://myapp.deeplinknow.com/launch"))
            .create(null).get()
        assertTrue(DLN.handleIntent(first.intent))
        assertTrue(idleMainUntil { received.size == 1 })
        // The process died and the user came back from Recents: the system re-creates the
        // activity with the original intent and its saved state, well outside the 3 s window.
        DLNLinkRouter.clock = { Long.MAX_VALUE / 2 }
        val restored = Robolectric.buildActivity(Activity::class.java, viewIntent("https://myapp.deeplinknow.com/launch"))
            .create(Bundle()).get()
        assertTrue("still the app's link", DLN.handleIntent(restored.intent))
        settle()
        assertEquals(1, received.size)
        // A new tap on the same link (no saved state) is a new open.
        val again = Robolectric.buildActivity(Activity::class.java, viewIntent("https://myapp.deeplinknow.com/launch"))
            .create(null).get()
        assertTrue(DLN.handleIntent(again.intent))
        assertTrue(idleMainUntil { received.size == 2 })
    }

    // ---- SDK-89: hold ----

    @Test
    fun `SDK-89 hold keeps the latest link in pendingLink until releasePendingLink delivers it`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(hold = true))
        settle()
        assertNull(DLN.pendingLink)
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/first")))
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/second")))
        settle()
        assertTrue(received.isEmpty())
        assertEquals("/second", DLN.pendingLink?.path) // only the latest is kept
        assertTrue(DLN.releasePendingLink())
        assertNull(DLN.pendingLink)
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals("/second", received.single().path)
        assertFalse(DLN.releasePendingLink())

        // releasing ended the hold: the next link goes straight to the handler
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/third")))
        assertTrue(idleMainUntil { received.size == 2 })
        assertNull(DLN.pendingLink)
        assertEquals("/third", received[1].path)
    }

    @Test
    fun `SDK-89 releasePendingLink with nothing held still ends the hold`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(hold = true))
        settle()
        assertFalse(DLN.releasePendingLink())
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/open")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertNull(DLN.pendingLink)
    }

    @Test
    fun `SDK-89 a held deferred link waits in pendingLink`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        record(DLNLinkOptions(hold = true))
        assertTrue(idleMainUntil { DLN.pendingLink != null })
        assertEquals(DLNLinkType.DEFERRED, DLN.pendingLink?.type)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `SDK-89 pendingLinks holds the latest deferred and the latest direct link, deferred first`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        record(DLNLinkOptions(hold = true))
        assertTrue(idleMainUntil { DLN.pendingLink != null })
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/d1")))
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/d2")))
        settle()
        assertTrue(received.isEmpty())
        assertEquals(listOf(DLNLinkType.DEFERRED, DLNLinkType.DIRECT), DLN.pendingLinks.map { it.type })
        assertEquals(listOf("/promo", "/d2"), DLN.pendingLinks.map { it.path })
        assertEquals(DLN.pendingLinks.first(), DLN.pendingLink)

        assertTrue(DLN.releasePendingLink())
        assertTrue(DLN.pendingLinks.isEmpty())
        assertNull(DLN.pendingLink)
        assertTrue(idleMainUntil { received.size == 2 })
        assertEquals(listOf("/promo", "/d2"), received.map { it.path })
    }

    @Test
    fun `SDK-89 clearPendingLink drops every held link`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        record(DLNLinkOptions(hold = true))
        assertTrue(idleMainUntil { DLN.pendingLink != null })
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/x")))
        assertEquals(2, DLN.pendingLinks.size)
        DLN.clearPendingLink()
        assertTrue(DLN.pendingLinks.isEmpty())
        assertFalse(DLN.releasePendingLink())
        settle()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `SDK-89 clearPendingLink drops the held link and it is never delivered`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(hold = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/drop")))
        assertNotNull(DLN.pendingLink)
        DLN.clearPendingLink()
        assertNull(DLN.pendingLink)
        // the hold stays on after clear: the next link is held too
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/next")))
        settle()
        assertTrue(received.isEmpty())
        assertEquals("/next", DLN.pendingLink?.path)
        DLN.clearPendingLink()
        assertFalse(DLN.releasePendingLink())
        record()
        settle()
        assertTrue(received.isEmpty())
    }

    @Test
    fun `SDK-89 AND-39 registering again with hold off delivers the held link and stops holding`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(hold = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/gate")))
        assertEquals("/gate", DLN.pendingLink?.path)
        record(DLNLinkOptions(hold = false))
        assertTrue(idleMainUntil { received.size == 1 })
        assertNull(DLN.pendingLink)
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/after")))
        assertTrue(idleMainUntil { received.size == 2 })
        assertEquals(listOf("/gate", "/after"), received.map { it.path })
    }

    // ---- SDK-90: resolve ----

    @Test
    fun `SDK-90 AND-38 resolve is off by default - no request`() {
        noReferrer()
        initSdk()
        record()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/abc123")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertTrue(resolveRequests.isEmpty())
        assertNull(received.single().deeplinkId)
    }

    @Test
    fun `SDK-90 AND-38 resolve 200 fills deeplinkId, targetUrl, campaignId and metadata`() {
        noReferrer()
        resolve = {
            json("""{"deeplink": ${deeplinkJson(
                targetUrl = "https://yourapp.com/invite/8Fk2",
                metadata = """{ "fdl": { "short_link": "https://links.yourapp.com/abc123" } }""",
                fullPath = "/abc123?utm_source=x",
                queryParams = """{ "utm_source": "x" }""",
            )}}""")
        }
        initSdk()
        record(DLNLinkOptions(resolve = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/abc123?utm_source=x")))
        assertTrue(idleMainUntil { received.size == 1 })
        val link = received.single()
        assertEquals(DEEPLINK_ID, link.deeplinkId)
        assertEquals("https://yourapp.com/invite/8Fk2", link.targetUrl)
        assertEquals(DLNTestSupport.CAMPAIGN_ID, link.campaignId)
        assertNotNull(link.metadata?.get("fdl"))
        // path and query stay what the user opened
        assertEquals("/abc123", link.path)
        assertEquals("/abc123?utm_source=x", link.fullPath)
        assertEquals(DLNLinkType.DIRECT, link.type)
        assertEquals(DLNLinkSource.APP_LINK, link.source)

        val request = resolveRequests.single()
        assertEquals("GET", request.method)
        assertEquals("/abc123?utm_source=x", request.requestUrl!!.queryParameter("path"))
        assertEquals(DLNTestSupport.TEST_API_KEY, request.getHeader("x-api-key"))
        assertFalse(request.path!!.contains(DLNTestSupport.TEST_API_KEY))
    }

    @Test
    fun `SDK-90 AND-38 resolve 404 delivers the link unresolved`() {
        noReferrer()
        resolve = { json("""{"error":"Deeplink not found"}""", 404) }
        initSdk()
        record(DLNLinkOptions(resolve = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/unknown")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals("/unknown", received.single().path)
        assertNull(received.single().deeplinkId)
        assertEquals(1, resolveRequests.size)
    }

    @Test
    fun `SDK-90 AND-38 a resolve 5xx is not retried and delivers unresolved`() {
        noReferrer()
        resolve = { json("{}", 503) }
        DLN.init(context, DLNTestSupport.TEST_API_KEY, baseUrl = baseUrl(), retryAttempts = 2)
        runBlocking { DLN.awaitInitialization() }
        record(DLNLinkOptions(resolve = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/flaky")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertNull(received.single().deeplinkId)
        assertEquals(1, resolveRequests.size)
    }

    @Test
    fun `SDK-90 AND-38 a resolve timeout delivers the link unresolved within the timeout`() {
        noReferrer()
        resolve = { json("""{"deeplink": ${deeplinkJson()}}""").setHeadersDelay(5, TimeUnit.SECONDS) }
        initSdk(timeoutMs = 400)
        record(DLNLinkOptions(resolve = true))
        settle()
        val started = System.currentTimeMillis()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/slow")))
        assertTrue(idleMainUntil(4_000) { received.size == 1 })
        val elapsed = System.currentTimeMillis() - started
        assertTrue("delivered after ${elapsed}ms", elapsed < 2_500)
        assertNull(received.single().deeplinkId)
        assertEquals("/slow", received.single().path)
    }

    @Test
    fun `SDK-90 resolve never applies to scheme links`() {
        noReferrer()
        initSdk()
        record(DLNLinkOptions(resolve = true, schemes = setOf("myapp")))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("myapp://abc123")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertTrue(resolveRequests.isEmpty())
    }

    @Test
    fun `SDK-90 resolveLink returns the resolved link on 200 and the unresolved one on 404, null for other hosts`() {
        noReferrer()
        initSdk()
        resolve = { json("""{"deeplink": ${deeplinkJson()}}""") }
        val resolved = runBlocking { DLN.resolveLink("https://myapp.deeplinknow.com/promo?code=SAVE20") }!!
        assertEquals(DEEPLINK_ID, resolved.deeplinkId)
        assertEquals(DLNLinkType.DIRECT, resolved.type)
        assertEquals(DLNLinkSource.APP_LINK, resolved.source)

        resolve = { json("""{"error":"Deeplink not found"}""", 404) }
        val unresolved = runBlocking { DLN.resolveLink("https://myapp.deeplinknow.com/nothing") }!!
        assertNull(unresolved.deeplinkId)
        assertEquals("/nothing", unresolved.path)

        assertNull(runBlocking { DLN.resolveLink("https://evil.com/x") })
        settle(100)
        assertTrue("resolveLink delivers nothing to the handler", received.isEmpty())
    }

    @Test
    fun `SDK-90 resolveLink callback form runs on the main thread`() {
        noReferrer()
        initSdk()
        resolve = { json("""{"deeplink": ${deeplinkJson()}}""") }
        val results = CopyOnWriteArrayList<Any>()
        DLN.resolveLink("https://myapp.deeplinknow.com/promo", object : DLNLinkResultCallback {
            override fun onResult(link: DLNLink?) {
                results += (Looper.myLooper() == Looper.getMainLooper())
                results += (link?.deeplinkId ?: "null")
            }
        })
        assertTrue(idleMainUntil { results.size == 2 })
        assertEquals(listOf<Any>(true, DEEPLINK_ID), results.toList())
    }

    // ---- SDK-91: existing calls unchanged ----

    @Test
    fun `SDK-91 SDK-27 checkDeferredDeepLink and onLink together run the chain once and both get the link`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        initSdk()
        val direct = runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow()
        assertEquals(DeferredDeepLinkSource.REFERRER, direct?.source)
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(DLNLinkType.DEFERRED, received.single().type)
        assertEquals(direct!!.fullPath, received.single().fullPath)
        assertEquals(1, lookupCalls.get())
        assertEquals(0, matchCalls.get())
        // a later checkDeferredDeepLink still follows SDK-23
        assertNull(runBlocking { DLN.checkDeferredDeepLink() }.getOrThrow())
    }

    @Test
    fun `SDK-27 AND-42 concurrent calls with different minConfidence share one chain and each applies its own threshold`() {
        noReferrer()
        match = {
            json(DLNTestSupport.matchResponse(
                matchJson(45.0, "LOW", DLNTestSupport.deeplinkJson(id = "00000000-0000-4000-8000-000000000001")),
            )).setHeadersDelay(300, TimeUnit.MILLISECONDS)
        }
        initSdk()
        val strict = runBlocking {
            val call = async { DLN.checkDeferredDeepLink(minConfidence = 50.0) }
            record() // onLink's own run starts while the app's call is in flight
            call.await()
        }
        assertNull("45 is below the caller's 50", strict.getOrThrow())
        assertTrue("onLink has no threshold and gets the candidate", idleMainUntil { received.size == 1 })
        assertEquals("00000000-0000-4000-8000-000000000001", received.single().deeplinkId)
        assertEquals(1, matchCalls.get())
        settle()
        assertEquals(1, matchCalls.get())
        assertEquals(1, received.size)
    }

    @Test
    fun `SDK-91 AND-42 onLink gets a link the app's stricter checkDeferredDeepLink run found and rejected`() {
        noReferrer()
        match = {
            json(DLNTestSupport.matchResponse(
                matchJson(45.0, "LOW", DLNTestSupport.deeplinkJson(id = "00000000-0000-4000-8000-000000000001")),
            ))
        }
        initSdk()
        assertNull(runBlocking { DLN.checkDeferredDeepLink(minConfidence = 50.0) }.getOrThrow())
        record()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(1, matchCalls.get())
    }

    @Test
    fun `SDK-91 parseDeepLink, isValidDomain and checkDeferredDeepLink behave as before with a handler registered`() {
        noReferrer()
        initSdk()
        record()
        val parsed = DLN.parseDeepLink("https://myapp.deeplinknow.com/a?b=1")!!
        assertEquals("/a", parsed.path)
        assertTrue(DLN.isValidDomain("links.myapp.com"))
        assertFalse(DLN.isValidDomain("links.myapp.com.evil.com"))
        settle()
        assertTrue(runBlocking { DLN.checkDeferredDeepLink(force = true) }.isSuccess)
    }

    // ---- AND-33 / AND-39: carry-over ----

    @Test
    fun `AND-39 AND-33 the link handler and a held link carry over when init replaces the instance`() {
        noReferrer()
        initSdk("key-one")
        record(DLNLinkOptions(hold = true))
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/held")))
        initSdk("key-two")
        assertEquals("/held", DLN.pendingLink?.path)
        assertTrue(DLN.releasePendingLink())
        assertTrue(idleMainUntil { received.size == 1 })
        record()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/after-reinit")))
        assertTrue(idleMainUntil { received.size == 2 })
    }

    @Test
    fun `AND-39 a check that failed with a failed init runs again after a successful re-init`() {
        mockInstallReferrer(referrer = "fp_id=$FP_ID")
        init = { json("{}", 503) }
        initSdk()
        record()
        settle()
        assertTrue(received.isEmpty())
        init = { json(initResponse()) }
        initSdk()
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals(DLNLinkType.DEFERRED, received.single().type)
    }

    @Test
    fun `AND-39 subscription remove only removes its own handler`() {
        noReferrer()
        initSdk()
        val old = record()
        val newer = CopyOnWriteArrayList<DLNLink>()
        DLN.onLink { newer += it }
        old.remove()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/x")))
        assertTrue(idleMainUntil { newer.size == 1 })
        assertTrue(received.isEmpty())
    }

    @Test
    fun `SDK-86 SDK-56 the handler runs on the main thread and a throwing handler does not crash the SDK`() {
        noReferrer()
        initSdk()
        val onMain = CopyOnWriteArrayList<Boolean>()
        DLN.onLink { link ->
            onMain += (Looper.myLooper() == Looper.getMainLooper())
            if (link.path == "/boom") throw IllegalStateException("app bug")
        }
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/boom")))
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/fine")))
        assertTrue(idleMainUntil { onMain.size == 2 })
        assertEquals(listOf(true, true), onMain.toList())
    }

    // ---- SDK-92: fragment stripping in parsing ----

    @Test
    fun `SDK-92 SDK-30 parsing strips a dln fragment parameter from url and fullPath`() {
        val parsed = DLN.parseDeepLink("https://myapp.deeplinknow.com/promo?code=SAVE20#dln=$FP_ID")!!
        assertEquals("https://myapp.deeplinknow.com/promo?code=SAVE20", parsed.url)
        assertEquals("/promo?code=SAVE20", parsed.fullPath)
        assertEquals("/promo", parsed.path)
        assertEquals(mapOf<String, QueryValue>("code" to QueryValue.Single("SAVE20")), parsed.queryParams)

        // other fragment content is kept; only the dln parameter goes
        assertEquals(
            "https://myapp.deeplinknow.com/a#section",
            DLN.parseDeepLink("https://myapp.deeplinknow.com/a#section")!!.url,
        )
        assertEquals(
            "https://myapp.deeplinknow.com/a#x=1&y=2",
            DLN.parseDeepLink("https://myapp.deeplinknow.com/a#x=1&dln=$FP_ID&y=2")!!.url,
        )
        assertEquals(
            "https://myapp.deeplinknow.com/a",
            DLN.parseDeepLink("https://myapp.deeplinknow.com/a#dln=")!!.url,
        )
        // a query parameter named dln is the app's, not the fragment's
        assertEquals(
            "https://myapp.deeplinknow.com/a?dln=1",
            DLN.parseDeepLink("https://myapp.deeplinknow.com/a?dln=1")!!.url,
        )
    }

    @Test
    fun `SDK-92 handleIntent delivers a link without the dln fragment`() {
        noReferrer()
        initSdk()
        record()
        settle()
        assertTrue(DLN.handleIntent(viewIntent("https://myapp.deeplinknow.com/p?q=1#dln=$FP_ID")))
        assertTrue(idleMainUntil { received.size == 1 })
        assertEquals("https://myapp.deeplinknow.com/p?q=1", received.single().url)
        assertEquals("/p?q=1", received.single().fullPath)
    }
}
