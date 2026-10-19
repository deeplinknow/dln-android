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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * parseDeepLink / isValidDomain / handleDeepLink (no network for parsing).
 * docs/sdks/android.md AND-25..27, docs/sdks/overview.md SDK-30..38.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNParseDeepLinkTest {

    private lateinit var context: Context
    private lateinit var dln: DLN

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        dln = newDln(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    /** An instance that completed init for alias "myapp" with verified custom domain go.myapp.com. */
    private fun initialized(customDomain: String? = "go.myapp.com"): DLN {
        val server = MockWebServer().apply { start() }
        try {
            mockInstallReferrer(responseCode = InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
            server.enqueue(json(initResponse(customDomain, true)))
            val d = newDln(context)
            redirectTo(d, server)
            runBlocking { d.startAndAwait() }
            assertTrue(runBlocking { d.awaitInit() }.isSuccess)
            return d
        } finally {
            server.shutdown()
        }
    }

    // ---- Host rule after init (SDK-31, SDK-33, SDK-38, AND-25) ----

    @Test
    fun `FLOW-41 SDK-31 SDK-38 AND-25 after init only the exact alias and verified custom domains are accepted`() {
        val d = initialized()
        for (host in listOf("myapp.deeplinknow.com", "go.myapp.com", "MyApp.DeepLinkNow.com", "GO.MYAPP.COM", "myapp.deeplinknow.com.")) {
            assertTrue(host, d.isValidDomain(host))
        }
        for (host in listOf("otherapp.deeplinknow.com", "deeplinknow.com", "www.deeplinknow.com", "myapp.deeplink.now", "deeplink.now", "a.myapp.deeplinknow.com")) {
            assertFalse(host, d.isValidDomain(host))
        }
    }

    @Test
    fun `FLOW-41 SDK-33 lookalike URLs are rejected after init`() {
        val d = initialized()
        for (url in listOf(
            "https://deeplinknow.com.evil.com/p",
            "https://evil-deeplinknow.com/p",
            "https://evildeeplinknow.com/p",
            "https://evil.com/?u=x.deeplinknow.com",
            "https://evil.com#a.deeplinknow.com",
            "https://a.deeplinknow.com@evil.com/",
            "https://go.myapp.com.evil.com/p",
            "https://myapp.deeplink.now/p",
            "https://otherapp.deeplinknow.com/p",
        )) {
            assertNull(url, d.parseDeepLink(url))
            assertNull(url, d.parseLink(url))
        }
    }

    @Test
    fun `SDK-31 the host rule drops a port`() {
        val d = initialized()
        assertEquals("/promo", d.parseDeepLink("https://myapp.deeplinknow.com:443/promo")!!.first)
        assertTrue(d.isValidDomain("myapp.deeplinknow.com:8443"))
    }

    // ---- Host rule before init (SDK-37) ----

    @Test
    fun `SDK-37 before init any non-reserved alias deeplinknow_com host is accepted provisionally`() {
        for (host in listOf("myapp.deeplinknow.com", "otherapp.deeplinknow.com", "MyApp.DeepLinkNow.com")) {
            assertTrue(host, dln.isValidDomain(host))
            assertNotNull(host, dln.parseDeepLink("https://$host/p"))
        }
    }

    @Test
    fun `SDK-37 SDK-31 before init the apex, reserved subdomains, deeplink_now and custom domains are rejected`() {
        for (host in listOf(
            "deeplinknow.com", "www.deeplinknow.com", "app.deeplinknow.com", "api.deeplinknow.com",
            "docs.deeplinknow.com", "m.deeplinknow.com", "mail.deeplinknow.com",
            "deeplink.now", "myapp.deeplink.now", "go.myapp.com",
            "evil-deeplinknow.com", "deeplinknow.com.evil.com", "notdeeplinknow.com",
            "deeplinknow.co", "deeplinknow.com-evil.com", "xdeeplink.now", "evil.com", "",
        )) {
            assertFalse(host, dln.isValidDomain(host))
            assertNull(host, dln.parseDeepLink("https://$host/p"))
        }
    }

    @Test
    fun `SDK-33 rejects URLs that only mention the domain outside the host`() {
        for (url in listOf(
            "https://myapp.deeplinknow.com@evil.com/promo",
            "https://evil.com/myapp.deeplinknow.com/promo",
            "https://evil.com/?r=https://myapp.deeplinknow.com",
            "https://evil.com#.deeplinknow.com",
            "https://evil.com?.deeplinknow.com",
        )) {
            assertNull(url, dln.parseDeepLink(url))
        }
    }

    @Test
    fun `SDK-31 host comparison is case-insensitive`() {
        assertNotNull(dln.parseDeepLink("https://MyApp.DeepLinkNow.com/promo"))
        assertNotNull(dln.parseLink("HTTPS://MyApp.DeepLinkNow.com/promo"))
    }

    // ---- Schemes and malformed input (SDK-32, SDK-36) ----

    @Test
    fun `SDK-32 only http and https are accepted`() {
        assertNotNull(dln.parseLink("http://myapp.deeplinknow.com/p"))
        for (url in listOf(
            "myapp://myapp.deeplinknow.com/p", "dln://myapp.deeplinknow.com/p",
            "mailto:a@myapp.deeplinknow.com", "/promo?code=1", "myapp.deeplinknow.com/promo",
            "ftp://myapp.deeplinknow.com/p",
        )) {
            assertNull(url, dln.parseLink(url))
        }
    }

    @Test
    fun `SDK-36 malformed input returns null and never throws`() {
        for (url in listOf("", "not a url", "deeplinknow.com/promo", "https://", "https://%zz", "::::", "https://[::1", "\u0000")) {
            assertNull(url, dln.parseDeepLink(url))
            assertNull(url, dln.parseLink(url))
        }
    }

    // ---- Path / fullPath / query (SDK-30, SDK-34, SDK-35, SDK-36, AND-26) ----

    @Test
    fun `SDK-30 parseLink returns path, queryParams, fullPath and url`() {
        val url = "https://myapp.deeplinknow.com/product/123?referrer=social_share&campaign=summer"
        val parsed = dln.parseLink(url)!!
        assertEquals("/product/123", parsed.path)
        assertEquals("/product/123?referrer=social_share&campaign=summer", parsed.fullPath)
        assertEquals(url, parsed.url)
        assertEquals(
            linkedMapOf("referrer" to QueryValue.Single("social_share"), "campaign" to QueryValue.Single("summer")),
            parsed.queryParams,
        )
        assertEquals(listOf("referrer", "campaign"), parsed.queryParams.keys.toList())
    }

    @Test
    fun `SDK-30 the static DLN_parseDeepLink works with and without an instance`() {
        assertEquals("/p", DLN.parseDeepLink("https://myapp.deeplinknow.com/p")!!.path)
        DLNTestSupport.setSingleton(initialized())
        assertNull(DLN.parseDeepLink("https://otherapp.deeplinknow.com/p"))
        assertEquals("/x", DLN.parseDeepLink("https://go.myapp.com/x")!!.path)
        assertTrue(DLN.isValidDomain("go.myapp.com"))
    }

    @Test
    fun `SDK-34 empty path becomes slash, trailing slash is kept, fragment is excluded`() {
        assertEquals("/", dln.parseLink("https://myapp.deeplinknow.com")!!.path)
        assertEquals("/", dln.parseLink("https://myapp.deeplinknow.com?x=1")!!.path)
        assertEquals("/?x=1", dln.parseLink("https://myapp.deeplinknow.com?x=1")!!.fullPath)
        assertEquals("/shop/", dln.parseLink("https://myapp.deeplinknow.com/shop/")!!.path)
        val withFragment = dln.parseLink("https://myapp.deeplinknow.com/promo?code=SAVE20#section-2")!!
        assertEquals("/promo", withFragment.path)
        assertEquals("/promo?code=SAVE20", withFragment.fullPath)
        assertEquals(mapOf("code" to QueryValue.Single("SAVE20")), withFragment.queryParams)
    }

    @Test
    fun `SDK-34 SDK-36 AND-26 path and fullPath are not decoded or re-encoded`() {
        val parsed = dln.parseLink("https://myapp.deeplinknow.com/caf%C3%A9/a%20b/@x+y,z?q=red+shoes&z=%2F")!!
        assertEquals("/caf%C3%A9/a%20b/@x+y,z", parsed.path)
        assertEquals("/caf%C3%A9/a%20b/@x+y,z?q=red+shoes&z=%2F", parsed.fullPath)
    }

    @Test
    fun `SDK-35 AND-26 query values are decoded like URLSearchParams`() {
        val q = dln.parseLink(
            "https://myapp.deeplinknow.com/p?q=hello%20world&redirect=%2Fcart%3Fid%3D1&emoji=%F0%9F%8E%89&amp=a%26b&plus=red+shoes&k%20ey=v"
        )!!.queryParams
        assertEquals(QueryValue.Single("hello world"), q["q"])
        assertEquals(QueryValue.Single("/cart?id=1"), q["redirect"])
        assertEquals(QueryValue.Single("🎉"), q["emoji"])
        assertEquals(QueryValue.Single("a&b"), q["amp"])
        assertEquals(QueryValue.Single("red shoes"), q["plus"])
        assertEquals(QueryValue.Single("v"), q["k ey"])
    }

    @Test
    fun `SDK-35 AND-26 repeated keys map to a list in order, flags map to true`() {
        val q = dln.parseLink("https://myapp.deeplinknow.com/p?tag=a&flag&tag=b&empty=&tag=&Tag=c")!!.queryParams
        assertEquals(QueryValue.Multiple(listOf("a", "b", "")), q["tag"])
        assertEquals(QueryValue.Flag, q["flag"])
        assertEquals(QueryValue.Flag, q["empty"])
        assertEquals(QueryValue.Single("c"), q["Tag"])
        assertEquals(listOf("tag", "flag", "empty", "Tag"), q.keys.toList())
    }

    @Test
    fun `SDK-35 malformed percent escapes are kept literally and empty segments skipped`() {
        val q = dln.parseLink("https://myapp.deeplinknow.com/p?a=%zz&&b=%E2%82&=x")!!.queryParams
        assertEquals(QueryValue.Single("%zz"), q["a"])
        assertEquals(QueryValue.Single("�"), q["b"])
        assertEquals(QueryValue.Single("x"), q[""])
    }

    @Test
    fun `SDK-35 QueryValue converts to the cross-platform plain shape (string, true, list)`() {
        assertEquals("x", QueryValue.Single("x").toPlain())
        assertEquals(true, QueryValue.Flag.toPlain())
        assertEquals(listOf("a", "b"), QueryValue.Multiple(listOf("a", "b")).toPlain())
    }

    // ---- Legacy Pair-returning parseDeepLink (deprecated, kept working) ----

    @Test
    fun `legacy parseDeepLink extracts path and single-valued params`() {
        val (path, params) = dln.parseDeepLink("https://myapp.deeplinknow.com/product/123?referrer=social_share&campaign=summer")!!
        assertEquals("/product/123", path)
        assertEquals(mapOf("referrer" to "social_share", "campaign" to "summer"), params)
    }

    @Test
    fun `legacy parseDeepLink keeps its 1_0 semantics - decoded path, empty root, flags as empty strings, first duplicate`() {
        val (path, params) = dln.parseDeepLink("https://myapp.deeplinknow.com?tag=a&tag=b&flag")!!
        assertEquals("", path)
        assertEquals("a", params["tag"])
        assertEquals("", params["flag"])
        assertEquals("/café/a b", dln.parseDeepLink("https://myapp.deeplinknow.com/caf%C3%A9/a%20b")!!.first)
        // but the host rule is the new, strict one (SDK-31)
        assertNull(dln.parseDeepLink("https://myapp.deeplink.now/p"))
    }

    // ---- handleDeepLink (AND-27) ----

    @Test
    fun `AND-27 handleDeepLink strips the leading slash in route, adds path and typed params, and invokes the callback`() {
        DLNTestSupport.setSingleton(dln)
        var received: DeepLinkData? = null
        DLN.setDeepLinkCallback(object : DeepLinkCallback {
            override fun onDeepLinkOpen(deepLinkData: DeepLinkData) {
                received = deepLinkData
            }
        })
        assertTrue(DLN.handleDeepLink("https://myapp.deeplinknow.com/promo/x?code=SAVE20&tag=a&tag=b"))
        val data = received!!
        assertEquals("promo/x", data.route)
        assertEquals("/promo/x", data.path)
        assertEquals("SAVE20", data.params["code"])
        assertEquals(QueryValue.Multiple(listOf("a", "b")), data.queryParams["tag"])
        // Old two-argument construction keeps compiling
        assertEquals("/r", DeepLinkData("r", emptyMap()).path)
    }

    @Test
    fun `AND-27 SDK-50 handleDeepLink rejects foreign domains and survives a throwing callback`() {
        DLNTestSupport.setSingleton(dln)
        DLN.setDeepLinkCallback(object : DeepLinkCallback {
            override fun onDeepLinkOpen(deepLinkData: DeepLinkData) = throw RuntimeException("app bug")
        })
        assertFalse(DLN.handleDeepLink("https://evil-deeplinknow.com/promo"))
        assertTrue(DLN.handleDeepLink("https://myapp.deeplinknow.com/promo"))
    }
}
