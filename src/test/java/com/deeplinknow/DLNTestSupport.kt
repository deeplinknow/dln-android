package com.deeplinknow

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test seams for DLN. They use the SDK's `internal` hooks (same Gradle module):
 *
 *  - the internal constructor builds an instance without DLN.init() (no background work);
 *  - `client` is replaced by one whose interceptor records the original URL and rewrites
 *    scheme/host/port to a MockWebServer (path + query are preserved), so tests can assert
 *    the real production URL;
 *  - retry / referrer backoff delays are shrunk so retry tests run fast.
 */
object DLNTestSupport {

    fun newDln(
        context: Context,
        apiKey: String = TEST_API_KEY,
        overrideScreenWidth: Int? = null,
        overrideScreenHeight: Int? = null,
        overridePixelRatio: Float? = null,
        enableLogs: Boolean = true,
        retryAttempts: Int = 2,
        baseUrl: String = "https://deeplinknow.com",
    ): DLN {
        val dln = DLN(
            DLNConfig(
                apiKey = apiKey,
                enableLogs = enableLogs,
                overrideScreenWidth = overrideScreenWidth,
                overrideScreenHeight = overrideScreenHeight,
                overridePixelRatio = overridePixelRatio,
                baseUrl = baseUrl,
                retryAttempts = retryAttempts,
            ),
            context,
        )
        dln.retryBaseDelayMs = 1
        dln.referrerRetryDelayMs = 1
        return dln
    }

    /** Points the DLN instance at [server]. Returns the list of original (pre-rewrite) URLs. */
    fun redirectTo(dln: DLN, server: MockWebServer): MutableList<HttpUrl> {
        val originalUrls = CopyOnWriteArrayList<HttpUrl>()
        val client = dln.client.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                originalUrls += original.url
                val rewritten = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()
        dln.client = client
        return originalUrls
    }

    /** Registers [dln] as the companion singleton (what DLN.init() does), or clears it. */
    fun setSingleton(dln: DLN?) {
        DLN.instance = dln
    }

    /**
     * A clean slate between tests and "fresh installs" inside one test: statics reset and the
     * SDK-83 init cache deleted, so a later init really calls /init. DLNInitCacheTest simulates
     * a cold start with [DLN.resetForTesting] instead, which keeps the cache.
     */
    fun resetStatics() {
        DLN.resetForTesting()
        try {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            java.io.File(context.noBackupFilesDir, "com.deeplinknow.initcache.json").delete()
        } catch (e: Exception) {
            // no Robolectric application (plain JVM tests)
        }
    }

    /** Routes MockWebServer responses by endpoint path (`/api/v1/sdk/<endpoint>`). */
    fun routingDispatcher(routes: Map<String, () -> MockResponse>): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(404)
                val handler = routes[path] ?: return MockResponse().setResponseCode(404)
                    .setBody("""{"error":"Not found"}""")
                return handler()
            }
        }

    fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    fun parseObject(body: String): JsonObject = JsonParser.parseString(body).asJsonObject

    /** Module root (Gradle runs unit tests with the module directory as working dir). */
    fun moduleFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File(System.getProperty("user.dir"), path)
    }

    /**
     * Mocks InstallReferrerClient.newBuilder(ctx).build() so that startConnection() immediately
     * calls back with [responseCode] and, for OK, returns [referrer] from getInstallReferrer().
     * [responseCodes] (if given) is consumed one per startConnection() call (for retry tests).
     */
    fun mockInstallReferrer(
        responseCode: Int = InstallReferrerClient.InstallReferrerResponse.OK,
        referrer: String? = null,
        afterSetup: ((InstallReferrerStateListener) -> Unit)? = null,
        startConnectionThrows: Throwable? = null,
        responseCodes: List<Int>? = null,
        disconnectInsteadOfSetup: Boolean = false,
    ): InstallReferrerClient {
        val details = mockk<ReferrerDetails>(relaxed = true)
        every { details.installReferrer } returns referrer
        val client = mockk<InstallReferrerClient>(relaxed = true)
        val listener = slot<InstallReferrerStateListener>()
        val codes = responseCodes?.toMutableList()
        if (startConnectionThrows != null) {
            every { client.startConnection(any()) } throws startConnectionThrows
        } else {
            every { client.startConnection(capture(listener)) } answers {
                if (disconnectInsteadOfSetup) {
                    listener.captured.onInstallReferrerServiceDisconnected()
                } else {
                    val code = if (codes != null && codes.isNotEmpty()) codes.removeAt(0) else responseCode
                    listener.captured.onInstallReferrerSetupFinished(code)
                    afterSetup?.invoke(listener.captured)
                }
            }
        }
        every { client.installReferrer } returns details
        val builder = mockk<InstallReferrerClient.Builder>(relaxed = true)
        every { builder.build() } returns client
        mockkStatic(InstallReferrerClient::class)
        every { InstallReferrerClient.newBuilder(any()) } returns builder
        return client
    }

    const val TEST_API_KEY = "dln_pk_test_0123456789abcdef"
    const val APP_ID = "6f1c2f0e-7a51-4f3e-9d43-1b2a3c4d5e6f"
    const val FP_ID = "0b6f3a1e-2c4d-4e5f-8a9b-0c1d2e3f4a5b"
    const val DEEPLINK_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    const val CAMPAIGN_ID = "11111111-2222-4333-8444-555555555555"

    /** Mirrors web/app/api/v1/sdk/init/route.ts with a verified custom domain. */
    fun initResponse(
        customDomain: String? = "links.myapp.com",
        verified: Boolean? = true,
        alias: String? = "myapp",
    ) = """
        {
          "app": {
            "id": "$APP_ID",
            "name": "My App",
            "timezone": "America/New_York",
            ${alias?.let { "\"alias\": \"$it\"," } ?: ""}
            "android_package_name": "com.myapp",
            "android_sha256_cert": "AA:BB:CC",
            "android_sha256_certificates": ["AA:BB:CC", "11:22:33"],
            "ios_bundle_id": null,
            "ios_app_store_id": null,
            "ios_app_prefix": null,
            "custom_domains": [
              { "domain": ${customDomain?.let { "\"$it\"" } ?: "null"}, "verified": ${verified ?: "null"} }
            ]
          },
          "account": {
            "status": "active",
            "credits_remaining": 1000,
            "rate_limits": { "matches_per_second": 1, "matches_per_day": 333 }
          }
        }
    """.trimIndent()

    /** Mirrors the deeplink object built by referrer-lookup/route.ts and match/route.ts. */
    fun deeplinkJson(
        id: String = DEEPLINK_ID,
        targetUrl: String = "promo?code=SAVE20",
        metadata: String = """{ "source": "facebook" }""",
        campaignId: String? = CAMPAIGN_ID,
        includeContext: Boolean = true,
        queryParams: String = """{ "code": "SAVE20" }""",
        fullPath: String = "/promo?code=SAVE20",
    ): String {
        val campaign = campaignId?.let { """"campaign_id": "$it",""" } ?: ""
        val context = if (includeContext) """,
            "query_params": $queryParams,
            "full_path": "$fullPath"""" else ""
        return """
          {
            "id": "$id",
            "target_url": "$targetUrl",
            "metadata": $metadata,
            $campaign
            "matched_at": "2026-10-18T10:35:00.000Z",
            "expires_at": "2026-10-18T11:35:00.000Z"$context
          }
        """.trimIndent()
    }

    fun referrerLookupSuccess(deeplink: String = deeplinkJson()) =
        """{ "success": true, "deeplink": $deeplink }"""

    /** Mirrors one entry of match/route.ts's ResponseSchema (including match_quality). */
    fun matchJson(score: Double = 87.5, quality: String? = "HIGH", deeplink: String? = deeplinkJson()) = """
        {
          "confidence_score": $score,
          ${quality?.let { "\"match_quality\": \"$it\"," } ?: ""}
          "match_details": {
            "ip_match": { "matched": true, "score": 40 },
            "device_match": {
              "matched": true, "score": 30,
              "components": { "platform": true, "os_version": true, "device_model": true, "hardware_fingerprint": false }
            },
            "time_proximity": { "score": 19.6, "time_difference_minutes": 3 },
            "locale_match": {
              "matched": true, "score": 10,
              "components": { "language": true, "timezone": true }
            }
          }${deeplink?.let { ""","deeplink": $it""" } ?: ""}
        }
    """.trimIndent()

    fun matchResponse(vararg matches: String) =
        """{ "matches": [${matches.joinToString(",")}], "ttl_seconds": 3600 }"""
}
