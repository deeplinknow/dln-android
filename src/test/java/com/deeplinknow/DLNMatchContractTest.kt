package com.deeplinknow

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.deeplinknow.DLNTestSupport.deeplinkJson
import com.deeplinknow.DLNTestSupport.initResponse
import com.deeplinknow.DLNTestSupport.json
import com.deeplinknow.DLNTestSupport.matchJson
import com.deeplinknow.DLNTestSupport.matchResponse
import com.deeplinknow.DLNTestSupport.newDln
import com.deeplinknow.DLNTestSupport.parseObject
import com.deeplinknow.DLNTestSupport.redirectTo
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * findDeferredUser() / findDeferredUserResult() against docs/api/sdk-endpoints.md §2.4
 * (AND-20, AND-21, AND-23; SDK-20..22, SDK-51, SDK-60..62).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DLNMatchContractTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
        DLNTestSupport.resetStatics()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "a1b2c3d4e5f60718")
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
        DLNTestSupport.resetStatics()
    }

    private fun match(response: MockResponse, dln: DLN = newDln(context, retryAttempts = 0)): MatchResponse? {
        server.enqueue(response)
        redirectTo(dln, server)
        return runBlocking { dln.findDeferredUser() }
    }

    /** New API: init first (SDK-06), then match. */
    private fun matchResult(response: MockResponse, dln: DLN = newDln(context, retryAttempts = 0)): Result<MatchResponse> {
        DLNTestSupport.mockInstallReferrer(responseCode = com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED)
        server.enqueue(json(initResponse()))
        server.enqueue(response)
        redirectTo(dln, server)
        runBlocking { dln.startAndAwait() }
        server.takeRequest(1, TimeUnit.SECONDS) // init
        return runBlocking { dln.findDeferredUserResult() }
    }

    private fun sentFingerprint(): JsonObject {
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        return parseObject(req.body.readUtf8())["fingerprint"].asJsonObject
    }

    // zod .datetime() default: UTC with "Z", optional fractional seconds, no offset
    private val zodDatetime = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")

    // ---- Request contract ----

    @Test
    fun `SDK-21 match is a POST to api v1 sdk match with x-api-key and a fingerprint wrapper`() {
        val dln = newDln(context)
        server.enqueue(json(matchResponse()))
        val urls = redirectTo(dln, server)
        runBlocking { dln.findDeferredUser() }

        assertEquals("https://deeplinknow.com/api/v1/sdk/match", urls.single().toString())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals(DLNTestSupport.TEST_API_KEY, req.getHeader("x-api-key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = parseObject(req.body.readUtf8())
        assertEquals(setOf("fingerprint"), body.keySet())
    }

    @Test
    fun `SDK-21 SDK-22 fingerprint satisfies the server's required fields and types`() {
        match(json(matchResponse()))
        val fp = sentFingerprint()

        for (key in listOf("user_agent", "os_version", "device_model", "language", "timezone")) {
            assertTrue("$key missing", fp.has(key) && fp[key].isJsonPrimitive && fp[key].asJsonPrimitive.isString)
        }
        assertEquals("android", fp["platform"].asString)
        assertTrue(fp["installed_at"].asString, zodDatetime.matches(fp["installed_at"].asString))
        assertTrue(fp["last_opened_at"].asString, zodDatetime.matches(fp["last_opened_at"].asString))
        for (key in listOf("device_id", "advertising_id", "vendor_id", "hardware_fingerprint")) {
            assertTrue("$key missing", fp.has(key))
        }
        assertEquals("a1b2c3d4e5f60718", fp["device_id"].asString)
        assertTrue(fp["screen_width"].asJsonPrimitive.isNumber)
        assertTrue(fp["screen_height"].asJsonPrimitive.isNumber)
        assertTrue(fp["pixel_ratio"].asJsonPrimitive.isNumber)
        // BCP-47 tag with a hyphen, IANA zone
        assertTrue(fp["language"].asString, fp["language"].asString.contains("-"))
        assertEquals(java.util.TimeZone.getDefault().id, fp["timezone"].asString)
    }

    @Test
    fun `AND-20 SDK-21 SDK-61 advertising_id and vendor_id are JSON null, never empty strings`() {
        match(json(matchResponse()))
        val fp = sentFingerprint()
        assertTrue(fp["advertising_id"].toString(), fp["advertising_id"].isJsonNull)
        assertTrue(fp["vendor_id"].toString(), fp["vendor_id"].isJsonNull)
    }

    @Test
    fun `AND-20 device_id key is sent as null when ANDROID_ID is unavailable`() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, null)
        match(json(matchResponse()))
        val fp = sentFingerprint()
        assertTrue("device_id key omitted: $fp", fp.has("device_id"))
        assertTrue(fp["device_id"].isJsonNull)
    }

    @Test
    fun `SDK-60 SDK-62 the fingerprint contains only the documented fields and no ip_address`() {
        match(json(matchResponse()))
        val fp = sentFingerprint()
        val allowed = setOf(
            "user_agent", "platform", "os_version", "device_model", "language", "timezone",
            "installed_at", "last_opened_at", "device_id", "advertising_id", "vendor_id",
            "hardware_fingerprint", "screen_width", "screen_height", "pixel_ratio",
        )
        assertTrue((fp.keySet() - allowed).toString(), (fp.keySet() - allowed).isEmpty())
        assertFalse(fp.has("ip_address"))
    }

    @Test
    fun `AND-23 screen overrides are sent as-is`() {
        match(json(matchResponse()), newDln(context, overrideScreenWidth = 1080, overrideScreenHeight = 2400, overridePixelRatio = 2.75f))
        val fp = sentFingerprint()
        assertEquals(1080, fp["screen_width"].asInt)
        assertEquals(2400, fp["screen_height"].asInt)
        assertEquals(2.75, fp["pixel_ratio"].asDouble, 0.0001)
    }

    @Test
    @Config(sdk = [30], qualifiers = "w411dp-h914dp-xxxhdpi")
    fun `AND-23 SDK-22 real screen size is sent in dp with density as pixel_ratio, without clamping large screens`() {
        match(json(matchResponse()))
        val fp = sentFingerprint()
        val metrics = context.resources.displayMetrics
        assertEquals(4.0, fp["pixel_ratio"].asDouble, 0.01)
        assertEquals(Math.round(metrics.widthPixels / metrics.density), fp["screen_width"].asInt)
        assertEquals(Math.round(metrics.heightPixels / metrics.density), fp["screen_height"].asInt)
        assertEquals(411, fp["screen_width"].asInt)
    }

    // ---- Response mapping ----

    @Test
    fun `SDK-20 zero matches is a successful empty response`() {
        val result = matchResult(json(matchResponse())).getOrThrow()
        assertTrue(result.matches.isEmpty())
        assertEquals(3600, result.ttlSeconds)
        assertTrue(match(json(matchResponse()))!!.matches.isEmpty())
    }

    @Test
    fun `SDK-20 one match maps every field the server sends`() {
        val result = match(json(matchResponse(matchJson())))!!
        val m = result.matches.single()
        assertEquals(87.5, m.confidenceScore, 0.0001)
        assertTrue(m.matchDetails.ipMatch.matched)
        assertEquals(40.0, m.matchDetails.ipMatch.score, 0.0001)
        assertFalse(m.matchDetails.deviceMatch.components.hardwareFingerprint)
        assertEquals(3, m.matchDetails.timeProximity.timeDifferenceMinutes)
        assertEquals(19.6, m.matchDetails.timeProximity.score, 0.0001)
        assertTrue(m.matchDetails.localeMatch.components.timezone)
        val d = m.deeplink!!
        assertEquals(DLNTestSupport.DEEPLINK_ID, d.id)
        assertEquals("promo?code=SAVE20", d.targetUrl)
        assertEquals("facebook", d.metadata["source"])
        assertEquals(DLNTestSupport.CAMPAIGN_ID, d.campaignId)
        assertEquals("2026-10-18T10:35:00.000Z", d.matchedAt)
        assertEquals("2026-10-18T11:35:00.000Z", d.expiresAt)
    }

    @Test
    fun `AND-21 SDK-20 match_quality, query_params and full_path are decoded`() {
        val m = matchResult(
            json(matchResponse(matchJson(deeplink = deeplinkJson(
                queryParams = """{ "code": "SAVE20", "flag": true, "tag": ["a", "b"] }""",
                fullPath = "/promo?code=SAVE20&flag&tag=a&tag=b",
            ))))
        ).getOrThrow().matches.single()
        assertEquals("HIGH", m.matchQuality)
        val d = m.deeplink!!
        assertEquals("/promo?code=SAVE20&flag&tag=a&tag=b", d.fullPath)
        assertEquals(
            linkedMapOf(
                "code" to QueryValue.Single("SAVE20"),
                "flag" to QueryValue.Flag,
                "tag" to QueryValue.Multiple(listOf("a", "b")),
            ),
            d.typedQueryParams,
        )
        // Gson round trip (apps that serialize the model) keeps the new fields
        val roundTrip = Gson().toJsonTree(d).asJsonObject
        assertTrue(roundTrip.has("query_params"))
        assertTrue(roundTrip.has("full_path"))
        assertTrue(Gson().toJsonTree(m).asJsonObject.has("match_quality"))
    }

    @Test
    fun `SDK-20 many matches keep server order (sorted by confidence) and are not filtered`() {
        val result = match(
            json(
                matchResponse(
                    matchJson(91.0, "HIGH", deeplinkJson(id = "00000000-0000-4000-8000-000000000001")),
                    matchJson(62.25, "MEDIUM", deeplinkJson(id = "00000000-0000-4000-8000-000000000002")),
                    matchJson(31.0, "LOW", deeplinkJson(id = "00000000-0000-4000-8000-000000000003")),
                )
            )
        )!!
        assertEquals(listOf(91.0, 62.25, 31.0), result.matches.map { it.confidenceScore })
        assertEquals(listOf("HIGH", "MEDIUM", "LOW"), result.matches.map { it.matchQuality })
        assertEquals("00000000-0000-4000-8000-000000000003", result.matches[2].deeplink!!.id)
    }

    @Test
    fun `SDK-51 match without deeplink and deeplink without campaign_id or context are tolerated`() {
        val result = match(
            json(
                matchResponse(
                    matchJson(deeplink = null),
                    matchJson(quality = null, deeplink = deeplinkJson(campaignId = null, includeContext = false)),
                )
            )
        )!!
        assertNull(result.matches[0].deeplink)
        assertNull(result.matches[1].deeplink!!.campaignId)
        assertNull(result.matches[1].deeplink!!.fullPath)
        assertNull(result.matches[1].matchQuality)
    }

    @Test
    fun `SDK-51 metadata null in a match does not leak null into the non-null field`() {
        val d = match(json(matchResponse(matchJson(deeplink = deeplinkJson(metadata = "null")))))!!.matches.single().deeplink!!
        @Suppress("SENSELESS_COMPARISON")
        assertTrue(d.metadata != null)
        assertTrue(d.metadata.isEmpty())
    }

    // ---- Errors ----

    @Test
    fun `legacy findDeferredUser returns null on HTTP errors`() {
        assertNull(match(json("""{"error":"Invalid API key"}""", 401)))
        assertNull(match(json("""{"error":"No credits remaining"}""", 403)))
        assertNull(match(json("""{"error":"Invalid request data","details":[]}""", 400)))
        assertNull(match(json("""{"error":"Rate limit exceeded"}""", 429)))
        assertNull(match(json("""{"error":"Failed to match fingerprint"}""", 500)))
    }

    @Test
    fun `SDK-55 findDeferredUserResult maps HTTP errors to typed codes`() {
        assertEquals(DLNErrorCode.NO_CREDITS, (matchResult(json("""{"error":"No credits remaining"}""", 403)).exceptionOrNull() as DLNException).code)
        DLNTestSupport.resetStatics(); unmockkAll()
        assertEquals(DLNErrorCode.BAD_REQUEST, (matchResult(json("""{"error":"Invalid request data"}""", 400)).exceptionOrNull() as DLNException).code)
        DLNTestSupport.resetStatics(); unmockkAll()
        assertEquals(DLNErrorCode.SERVER, (matchResult(json("""{"error":"Failed to match fingerprint"}""", 500)).exceptionOrNull() as DLNException).code)
    }

    @Test
    fun `SDK-54 network failure returns null (legacy) and network (new API)`() {
        assertNull(match(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)))
        val result = matchResult(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(DLNErrorCode.NETWORK, (result.exceptionOrNull() as DLNException).code)
    }

    @Test
    fun `AND-21 SDK-51 malformed JSON is invalidResponse (new API) and null (legacy), never thrown`() {
        try {
            assertNull(match(MockResponse().setResponseCode(200).setBody("<html>Login</html>")))
            val result = matchResult(MockResponse().setResponseCode(200).setBody("<html>Login</html>"))
            assertEquals(DLNErrorCode.INVALID_RESPONSE, (result.exceptionOrNull() as DLNException).code)
        } catch (e: Exception) {
            fail("findDeferredUser threw: $e")
        }
    }

    @Test
    fun `SDK-51 empty 200 body returns null (legacy) and invalidResponse (new API)`() {
        assertNull(match(MockResponse().setResponseCode(200).setBody("")))
        val result = matchResult(MockResponse().setResponseCode(200).setBody(""))
        assertEquals(DLNErrorCode.INVALID_RESPONSE, (result.exceptionOrNull() as DLNException).code)
    }
}
