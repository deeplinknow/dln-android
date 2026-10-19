package com.deeplinknow

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.installreferrer.api.InstallReferrerClient
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.mockInstallReferrer
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.redirectTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * checkClipboard() / hasDeepLinkToken(): AND-28, AND-29, SDK-40..45.
 * The web landing page copies `window.location.href` (web/components/universal-links.tsx),
 * i.e. an https URL on <alias>.deeplinknow.com or the app's custom domain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNClipboardTest {

    private lateinit var context: Context
    private lateinit var dln: DLN

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        dln = newDln(context)
        DLNTestSupport.setSingleton(dln)
    }

    @After
    fun tearDown() {
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun copy(text: String?) {
        if (text == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("dln", text))
    }

    private fun check(text: String?, instance: DLN = dln): String? {
        copy(text)
        return runBlocking { instance.checkClipboard() }
    }

    private fun initialized(): DLN {
        val server = MockWebServer().apply { start() }
        try {
            mockInstallReferrer(responseCode = InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
            server.enqueue(json(initResponse("links.myapp.com", true)))
            val d = newDln(context)
            redirectTo(d, server)
            runBlocking { d.startAndAwait() }
            return d
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `SDK-40 returns the URL the web page copied`() {
        val url = "https://myapp.deeplinknow.com/promo?code=SAVE20"
        assertEquals(url, check(url))
    }

    @Test
    fun `SDK-40 AND-28 surrounding whitespace is trimmed`() {
        assertEquals("https://myapp.deeplinknow.com/promo", check("  https://myapp.deeplinknow.com/promo\n"))
    }

    @Test
    fun `SDK-40 returns null for empty clipboard, plain text, foreign URLs and text that only contains a link`() {
        assertNull(check(null))
        assertNull(check("hello world"))
        assertNull(check("https://evil.com/promo"))
        assertNull(check("https://evil-deeplinknow.com/promo"))
        assertNull(check("https://deeplinknow.com.evil.com/promo"))
        assertNull(check("see https://myapp.deeplinknow.com/promo"))
        assertNull(check("https://myapp.deeplinknow.com/promo and more"))
        assertNull(check("myapp://myapp.deeplinknow.com/promo"))
    }

    @Test
    fun `SDK-37 before init the provisional rule applies and custom domains are rejected`() {
        val fresh = newDln(context)
        assertEquals("https://myapp.deeplinknow.com/promo", check("https://myapp.deeplinknow.com/promo", fresh))
        assertNull(check("https://links.myapp.com/promo", fresh))
        assertNull(check("https://deeplinknow.com/promo", fresh))
    }

    @Test
    fun `FLOW-14 SDK-31 AND-28 after init the verified custom domain and alias are accepted, other tenants rejected`() {
        val d = initialized()
        assertEquals("https://links.myapp.com/promo", check("https://links.myapp.com/promo", d))
        assertEquals("https://myapp.deeplinknow.com/promo", check("https://myapp.deeplinknow.com/promo", d))
        assertNull(check("https://otherapp.deeplinknow.com/promo", d))
        assertNull(check("https://myapp.deeplink.now/promo", d))
    }

    @Test
    fun `FLOW-14 AND-28 SDK-33 rejects foreign hosts whose fragment, query or userinfo mentions deeplinknow_com`() {
        assertNull(check("https://evil.com#.deeplinknow.com"))
        assertNull(check("https://evil.com?.deeplinknow.com"))
        assertNull(check("https://myapp.deeplinknow.com@evil.com/"))
    }

    @Test
    fun `AND-28 accepts a valid URL with port or query directly after the host`() {
        assertEquals("https://myapp.deeplinknow.com?code=SAVE20", check("https://myapp.deeplinknow.com?code=SAVE20"))
        assertEquals("https://myapp.deeplinknow.com:443/promo", check("https://myapp.deeplinknow.com:443/promo"))
    }

    @Test
    fun `AND-28 SDK-45 no clip available (Android 10+ without focus) or SecurityException returns null`() {
        val cm = mockk<ClipboardManager>()
        every { cm.primaryClip } returns null
        every { cm.hasPrimaryClip() } returns false
        val ctx = spyk(context)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        val noFocus = newDln(ctx)
        assertNull(runBlocking { noFocus.checkClipboard() })

        every { cm.primaryClip } throws SecurityException("not focused")
        assertNull(runBlocking { noFocus.checkClipboard() })
    }

    @Test
    fun `SDK-43 the SDK never writes or clears the clipboard`() {
        val cm = mockk<ClipboardManager>(relaxed = true)
        every { cm.primaryClip } returns ClipData.newPlainText("x", "https://myapp.deeplinknow.com/p")
        every { cm.hasPrimaryClip() } returns true
        every { cm.primaryClipDescription } returns ClipDescription("x", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        val ctx = spyk(context)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        val d = newDln(ctx)
        runBlocking { d.checkClipboard(); d.hasDeepLinkToken() }
        verify(exactly = 0) { cm.setPrimaryClip(any()) }
        verify(exactly = 0) { cm.clearPrimaryClip() }
    }

    @Test
    fun `SDK-44 non-DLN clipboard content is never logged`() {
        ShadowLog.clear()
        check("my secret note 1234")
        check("https://evil.com/private-token")
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("secret note") || it.msg.contains("private-token") })
    }

    // ---- hasDeepLinkToken (AND-29, SDK-41) ----

    @Test
    fun `AND-29 SDK-41 hasDeepLinkToken is true for what the web page copies`() {
        copy("https://myapp.deeplinknow.com/promo?code=SAVE20")
        assertTrue(runBlocking { dln.hasDeepLinkToken() })
    }

    @Test
    fun `AND-29 hasDeepLinkToken is false for an empty clipboard`() {
        copy(null)
        assertFalse(runBlocking { dln.hasDeepLinkToken() })
    }

    @Test
    fun `AND-29 SDK-41 hasDeepLinkToken uses the clip description and never reads the clip`() {
        val cm = mockk<ClipboardManager>(relaxed = true)
        every { cm.hasPrimaryClip() } returns true
        every { cm.primaryClipDescription } returns ClipDescription("x", arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST))
        val ctx = spyk(context)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        val d = newDln(ctx)
        assertTrue(runBlocking { d.hasDeepLinkToken() })
        verify(exactly = 0) { cm.primaryClip }

        every { cm.primaryClipDescription } returns ClipDescription("x", arrayOf("image/png"))
        assertFalse(runBlocking { d.hasDeepLinkToken() })
        verify(exactly = 0) { cm.primaryClip }
    }
}
