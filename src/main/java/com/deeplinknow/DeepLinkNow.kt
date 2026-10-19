package com.deeplinknow

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------------------------

data class DLNConfig(
    val apiKey: String,
    val enableLogs: Boolean = false,
    val overrideScreenWidth: Int? = null,
    val overrideScreenHeight: Int? = null,
    val overridePixelRatio: Float? = null,
    /** Server base URL (SDK-01). A trailing `/` is tolerated. Must be https (except dev hosts). */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Per-request timeout in milliseconds (connect, read, write and whole call). */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Retries for transient failures (network, timeout, 5xx, 429), with exponential backoff. */
    val retryAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
) {
    /** Never prints the API key (SDK-63): only its last four characters. */
    override fun toString(): String =
        "DLNConfig(apiKey=${maskApiKey(apiKey)}, enableLogs=$enableLogs, overrideScreenWidth=$overrideScreenWidth, " +
            "overrideScreenHeight=$overrideScreenHeight, overridePixelRatio=$overridePixelRatio, baseUrl=$baseUrl, " +
            "timeoutMs=$timeoutMs, retryAttempts=$retryAttempts)"

    companion object {
        const val DEFAULT_BASE_URL = "https://deeplinknow.com"
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_RETRY_ATTEMPTS = 2

        internal fun maskApiKey(key: String): String =
            if (key.length <= 4) "…" else "…" + key.takeLast(4)
    }
}

// ---------------------------------------------------------------------------------------------
// Results and errors (new in 1.1)
// ---------------------------------------------------------------------------------------------

/** Error codes shared by iOS, Android and React Native (SDK-55). [value] is the wire name. */
enum class DLNErrorCode(val value: String) {
    NOT_INITIALIZED("notInitialized"),
    INVALID_API_KEY("invalidApiKey"),
    ACCOUNT_INACTIVE("accountInactive"),
    NO_CREDITS("noCredits"),
    RATE_LIMITED("rateLimited"),
    BAD_REQUEST("badRequest"),
    SERVER("server"),
    NETWORK("network"),
    INVALID_RESPONSE("invalidResponse"),
}

/** The error delivered through `Result.failure` by the 1.1 APIs. Never thrown at the app. */
class DLNException(
    val code: DLNErrorCode,
    /** HTTP status when the error came from a response. */
    val httpStatus: Int? = null,
    /** The server's `error` / `message` string, when there was one. */
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception(
    buildString {
        append(code.value)
        httpStatus?.let { append(" (HTTP ").append(it).append(')') }
        serverMessage?.let { append(": ").append(it) }
    },
    cause,
)

/** What a successful initialization learned (SDK-03). */
data class DLNAppInfo(
    val appId: String,
    val alias: String?,
    val name: String?,
    /** `{alias}.deeplinknow.com` plus verified custom domains, lowercased. */
    val linkDomains: Set<String>,
    val accountStatus: String?,
    val creditsRemaining: Int?,
)

enum class MatchQuality {
    HIGH, MEDIUM, LOW;

    companion object {
        /** Server thresholds (SDK-16): ≥75 HIGH, ≥50 MEDIUM, else LOW. */
        @JvmStatic
        fun fromScore(score: Double): MatchQuality = when {
            score >= 75 -> HIGH
            score >= 50 -> MEDIUM
            else -> LOW
        }

        @JvmStatic
        fun parse(value: String?): MatchQuality? = when (value?.uppercase(Locale.ROOT)) {
            "HIGH" -> HIGH
            "MEDIUM" -> MEDIUM
            "LOW" -> LOW
            else -> null
        }
    }
}

enum class DeferredDeepLinkSource(val value: String) {
    REFERRER("referrer"),
    CLIPBOARD("clipboard"),
    FINGERPRINT("fingerprint"),
}

/** The result of [DLN.checkDeferredDeepLink] (SDK-15). Same fields on iOS and React Native. */
data class DeferredDeepLink(
    /** `fullPath` up to the first `?`, not decoded, starts with `/`. */
    val path: String,
    /** Query parameters with URLSearchParams semantics (string | true | list). */
    val queryParams: Map<String, QueryValue>,
    /** `/` + path + `?query` as tapped (server `full_path`). */
    val fullPath: String,
    /** Absolute https URL of the link on the app's link domain. */
    val url: String,
    val source: DeferredDeepLinkSource,
    /** 0–100. 100 for referrer results. */
    val confidenceScore: Double,
    val matchQuality: MatchQuality,
    val deeplinkId: String? = null,
    val targetUrl: String? = null,
    val campaignId: String? = null,
    val metadata: Map<String, Any>? = null,
    val matchedAt: String? = null,
    val expiresAt: String? = null,
    /** Score breakdown, fingerprint results only. */
    val matchDetails: MatchDetails? = null,
)

/** Java-friendly callback for [DLN.checkDeferredDeepLink]. Exactly one of the two is non-null, or both null for "no link". */
interface DeferredDeepLinkCallback {
    fun onResult(link: DeferredDeepLink?, error: DLNException?)
}

// ---------------------------------------------------------------------------------------------
// Models (1.0, kept; new fields are additive)
// ---------------------------------------------------------------------------------------------

data class DeepLinkData(
    /** Path without the leading `/`, decoded (1.0 behaviour, kept for compatibility). */
    val route: String,
    /** Legacy single-valued query map (first value of a repeated key, `""` for flags). */
    val params: Map<String, String>,
    /** Path with the leading `/`, not decoded; agrees with [DLN.parseDeepLink] (AND-27). */
    val path: String = "/$route",
    /** Query parameters with URLSearchParams semantics (SDK-35). */
    val queryParams: Map<String, QueryValue> = emptyMap(),
)

interface DeepLinkCallback {
    fun onDeepLinkOpen(deepLinkData: DeepLinkData)
}

data class Fingerprint(
    @SerializedName("user_agent") val userAgent: String,
    val platform: String = "android", // Must be "ios" or "android"
    @SerializedName("os_version") val osVersion: String,
    @SerializedName("device_model") val deviceModel: String,
    val language: String,
    val timezone: String,
    @SerializedName("installed_at") val installedAt: String, // yyyy-MM-dd'T'HH:mm:ss'Z' (UTC)
    @SerializedName("last_opened_at") val lastOpenedAt: String,
    @SerializedName("device_id") val deviceId: String?,
    /** Always null: the SDK never reads the advertising ID (SDK-61). */
    @SerializedName("advertising_id") val advertisingId: String? = null,
    /** Always null on Android (SDK-22). */
    @SerializedName("vendor_id") val vendorId: String? = null,
    @SerializedName("hardware_fingerprint") val hardwareFingerprint: String?,
    @SerializedName("screen_width") val screenWidth: Int?,
    @SerializedName("screen_height") val screenHeight: Int?,
    @SerializedName("pixel_ratio") val pixelRatio: Float?
)

data class DeeplinkMatch(
    val id: String,
    @SerializedName("target_url") val targetUrl: String,
    /** Never null (`{}` when the link has no metadata). */
    val metadata: Map<String, Any>,
    @SerializedName("campaign_id") val campaignId: String?,
    @SerializedName("matched_at") val matchedAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    /** Server `query_params`: values are `String`, `true` or `List<String>`. See [typedQueryParams]. */
    @SerializedName("query_params") val queryParams: Map<String, Any>? = null,
    /** Server `full_path`: the tapped path + query. */
    @SerializedName("full_path") val fullPath: String? = null,
) {
    /** [queryParams] as [QueryValue]s (empty when the server sent none). */
    val typedQueryParams: Map<String, QueryValue>
        get() {
            val out = LinkedHashMap<String, QueryValue>()
            queryParams?.forEach { (k, v) -> QueryValue.fromPlain(v)?.let { out[k] = it } }
            return out
        }
}

data class MatchComponentDetails(
    val matched: Boolean,
    val score: Double
)

data class DeviceMatchDetails(
    val matched: Boolean,
    val score: Double,
    val components: DeviceComponents
) {
    data class DeviceComponents(
        val platform: Boolean,
        @SerializedName("os_version") val osVersion: Boolean,
        @SerializedName("device_model") val deviceModel: Boolean,
        @SerializedName("hardware_fingerprint") val hardwareFingerprint: Boolean
    )
}

data class LocaleMatchDetails(
    val matched: Boolean,
    val score: Double,
    val components: LocaleComponents
) {
    data class LocaleComponents(
        val language: Boolean,
        val timezone: Boolean
    )
}

data class TimeProximityDetails(
    val score: Double,
    @SerializedName("time_difference_minutes") val timeDifferenceMinutes: Int
)

data class MatchDetails(
    @SerializedName("ip_match") val ipMatch: MatchComponentDetails,
    @SerializedName("device_match") val deviceMatch: DeviceMatchDetails,
    @SerializedName("time_proximity") val timeProximity: TimeProximityDetails,
    @SerializedName("locale_match") val localeMatch: LocaleMatchDetails
)

data class Match(
    val deeplink: DeeplinkMatch?,
    @SerializedName("confidence_score") val confidenceScore: Double,
    @SerializedName("match_details") val matchDetails: MatchDetails,
    /** Server label: "HIGH" | "MEDIUM" | "LOW" (null from older servers). */
    @SerializedName("match_quality") val matchQuality: String? = null,
)

data class MatchResponse(
    val matches: List<Match>,
    @SerializedName("ttl_seconds") val ttlSeconds: Int
)

data class InitResponse(
    val app: App,
    val account: Account
) {
    data class App(
        val id: String,
        val name: String,
        val timezone: String,
        @SerializedName("android_package_name") val androidPackageName: String?,
        @SerializedName("android_sha256_cert") val androidSha256Cert: String?,
        @SerializedName("ios_bundle_id") val iosBundleId: String?,
        @SerializedName("ios_app_store_id") val iosAppStoreId: String?,
        @SerializedName("ios_app_prefix") val iosAppPrefix: String?,
        @SerializedName("custom_domains") val customDomains: List<CustomDomain>,
        val alias: String? = null,
        @SerializedName("android_sha256_certificates") val androidSha256Certificates: List<String>? = null,
    ) {
        data class CustomDomain(
            val domain: String?,
            val verified: Boolean?
        )
    }

    data class Account(
        val status: String,
        @SerializedName("credits_remaining") val creditsRemaining: Int,
        @SerializedName("rate_limits") val rateLimits: RateLimits
    ) {
        data class RateLimits(
            @SerializedName("matches_per_second") val matchesPerSecond: Int,
            @SerializedName("matches_per_day") val matchesPerDay: Int
        )
    }
}

data class ReferrerLookupResponse(
    val success: Boolean,
    val deeplink: DeeplinkMatch?,
    val message: String?
)

data class ReferrerData(
    val referrerString: String,
    val fpId: String?,
    val deeplinkId: String?,
    val deeplinkData: DeeplinkMatch?,
    val processedAt: Long
)

// ---------------------------------------------------------------------------------------------
// SDK
// ---------------------------------------------------------------------------------------------

class DLN internal constructor(
    internal val config: DLNConfig,
    context: Context,
) {
    /** Only the application context is kept (AND-05). */
    internal val appContext: Context = context.applicationContext ?: context

    private val job = SupervisorJob()

    /** SDK-owned scope; nothing reaches the default uncaught-exception handler (AND-08). */
    internal val scope = CoroutineScope(
        job + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            warn("Unexpected internal error: ${e.javaClass.simpleName}")
        }
    )

    internal var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        // The SDK does its own bounded, logged retries (SDK-53).
        .retryOnConnectionFailure(false)
        .build()

    /** Base delay for HTTP retry backoff (0.5 s, 1 s, …). Shrunk in tests. */
    internal var retryBaseDelayMs = 500L

    /** Base delay between Install Referrer connection attempts. Shrunk in tests. */
    internal var referrerRetryDelayMs = 1_000L

    /** Wall clock for the init cache (SDK-83). Replaced in tests. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    private val baseUrl = config.baseUrl.trim().trimEnd('/')
    private val installTime = formatDateToISO8601(Date())

    @Volatile internal var initResponse: InitResponse? = null
    @Volatile private var linkDomains: Set<String>? = null
    @Volatile private var aliasUnknown = false
    @Volatile private var primaryDomain: String? = null
    @Volatile private var referrerData: ReferrerData? = null
    @Volatile private var deepLinkCallback: DeepLinkCallback? = null

    /** Set when the clipboard was read (tests assert init and the Android chain never do). */
    @Volatile internal var clipboardRead = false

    private val startLock = Any()
    @Volatile private var initDeferred: Deferred<Result<DLNAppInfo>>? = null
    @Volatile private var initOutcome: Result<DLNAppInfo>? = null

    private val referrerMutex = Mutex()
    @Volatile private var referrerDeferred: Deferred<ReferrerOutcome>? = null
    @Volatile private var lastReferrerOutcome: ReferrerOutcome? = null

    private val checkLock = Any()
    private val inFlightChecks = HashMap<Pair<Double?, Boolean>, Deferred<Result<DeferredDeepLink?>>>()

    private val stateLock = Any()
    @Volatile private var legacyMigrated = false

    /** First-launch flag + referrer cache, in noBackupFilesDir (SDK-25, AND-22). */
    internal val stateFile: File by lazy {
        val dir = try {
            appContext.noBackupFilesDir
        } catch (e: Exception) {
            appContext.filesDir
        }
        File(dir, "com.deeplinknow.state.json")
    }

    /** SDK-83: the last successful /init response, beside the state file. */
    internal val initCacheFile: File by lazy { File(stateFile.parentFile, INIT_CACHE_FILE) }

    // ---- logging (SDK-70/71) ----

    private fun log(message: String) {
        if (config.enableLogs) Log.d(TAG, message)
    }

    private fun warn(message: String) {
        Log.w(TAG, message)
    }

    // ---- lifecycle ----

    /** Starts init and referrer processing once. Never blocks. */
    internal fun start() {
        synchronized(startLock) {
            if (initDeferred != null) return
            initDeferred = scope.async { runInit() }
        }
        registerPendingCallback()
        scope.launch { awaitReferrer() }
    }

    internal fun shutdown() {
        try {
            scope.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    internal val isInitializedSuccessfully: Boolean
        get() = initOutcome?.isSuccess == true

    internal val initFailed: Boolean
        get() = initOutcome?.isFailure == true

    internal suspend fun awaitInit(): Result<DLNAppInfo> {
        val deferred = initDeferred ?: return Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED))
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = e))
        }
    }

    /** Test/compat helper: start, then wait for init and the referrer step. */
    internal suspend fun startAndAwait() {
        start()
        awaitInit()
        awaitReferrer()
    }

    /**
     * 1.0 entry point that re-ran init. Since 1.1 init runs once per instance (AND-09): this starts
     * it if needed and waits for it. Use [DLN.init] / [DLN.awaitInitialization] instead.
     */
    @Deprecated(
        "Not public API. DLN.init() starts initialization; await it with DLN.awaitInitialization().",
        ReplaceWith("DLN.awaitInitialization()"),
    )
    suspend fun initializeSDK() {
        try {
            startAndAwait()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("initializeSDK failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runInit(): Result<DLNAppInfo> {
        val cached = if (claimInitCache()) readInitCache() else null
        val result: Result<DLNAppInfo> = if (cached != null) {
            log("init: from the device cache (no /init request)")
            Result.success(applyInit(cached))
        } else try {
            log("init: POST /api/v1/sdk/init")
            val response = execute("POST", "init", body = initBody())
            if (response.code !in 200..299) {
                val error = errorFor(response)
                if (error.code == DLNErrorCode.INVALID_API_KEY || error.code == DLNErrorCode.ACCOUNT_INACTIVE) {
                    clearInitCache()
                }
                Result.failure(error)
            } else {
                val parsed = DLNJson.decodeInitResponse(DLNJson.parse(response.body))
                val info = applyInit(parsed)
                writeInitCache(response.body.orEmpty())
                Result.success(info)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DLNException) {
            Result.failure(e)
        } catch (e: DLNJson.Invalid) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, httpStatus = 200, serverMessage = e.message))
        } catch (e: Exception) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
        }
        initOutcome = result
        result.fold(
            onSuccess = { log("init: ok, link domains ${it.linkDomains}") },
            onFailure = { warn("Initialization failed: ${it.message}") },
        )
        return result
    }

    /**
     * BILL-20: `{"device_id": <ANDROID_ID or null>}` so MAU is counted per device, not per IP.
     * FP-21: `"sdk": "android/<version>"` so the server can see which SDKs count MAU.
     */
    private fun initBody(): String = JsonObject().apply {
        val id = androidId()
        if (id == null) add("device_id", JsonNull.INSTANCE) else addProperty("device_id", id)
        addProperty("sdk", "android/$SDK_VERSION")
    }.toString()

    // ---- init cache (SDK-83) ----

    /**
     * Only the first init of a process may use the cache; a later [DLN.init] with another key,
     * or after a failure, always asks the server (SDK-07).
     */
    private fun claimInitCache(): Boolean = synchronized(Companion.lock) {
        val first = !initCacheConsulted
        initCacheConsulted = true
        first
    }

    /**
     * The cached response when it was saved for this key and base URL, less than 24 hours ago,
     * in the current UTC month (so every device still reaches /init once a month: BILL-19).
     */
    private fun readInitCache(): InitResponse? = try {
        if (!initCacheFile.exists()) {
            null
        } else {
            val json = JsonParser.parseString(initCacheFile.readText()).asJsonObject
            val savedAt = json.get("saved_at").asLong
            val age = now() - savedAt
            val usable = json.get("format").asInt == INIT_CACHE_FORMAT &&
                json.get("api_key").asString == config.apiKey &&
                json.get("base_url").asString == baseUrl &&
                age in 0 until INIT_CACHE_MAX_AGE_MS &&
                utcMonth(savedAt) == utcMonth(now())
            if (usable) DLNJson.decodeInitResponse(DLNJson.parse(json.get("response").asString)) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun writeInitCache(body: String) {
        try {
            val json = JsonObject().apply {
                addProperty("format", INIT_CACHE_FORMAT)
                addProperty("api_key", config.apiKey)
                addProperty("base_url", baseUrl)
                addProperty("saved_at", now())
                addProperty("response", body)
            }
            initCacheFile.writeText(json.toString())
        } catch (e: Exception) {
            log("init: could not write the cache (${e.javaClass.simpleName})")
        }
    }

    private fun clearInitCache() {
        try {
            initCacheFile.delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun utcMonth(millis: Long): String {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = millis
        return "%04d-%02d".format(Locale.ROOT, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH) + 1)
    }

    private fun applyInit(response: InitResponse): DLNAppInfo {
        val alias = response.app.alias?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val customs = response.app.customDomains
            .filter { it.verified == true && !it.domain.isNullOrBlank() }
            .map { DLNLinks.normalizeHost(it.domain) }
            .filter { it.isNotEmpty() }
        val domains = LinkedHashSet<String>()
        alias?.let { domains += "$it${DLNLinks.DLN_SUFFIX}" }
        domains += customs
        initResponse = response
        aliasUnknown = alias == null
        primaryDomain = customs.firstOrNull() ?: alias?.let { "$it${DLNLinks.DLN_SUFFIX}" }
        linkDomains = domains
        return DLNAppInfo(
            appId = response.app.id,
            alias = alias,
            name = response.app.name,
            linkDomains = domains,
            accountStatus = response.account.status,
            creditsRemaining = response.account.creditsRemaining,
        )
    }

    private fun registerPendingCallback() {
        Companion.pendingCallback?.let { callback ->
            log("Registering pending deep link callback")
            setDeepLinkCallbackInternal(callback)
            Companion.pendingCallback = null
        }
    }

    // ---- HTTP ----

    internal class HttpResponse(val code: Int, val body: String?, val retryAfterMs: Long?)

    private fun endpointUrl(endpoint: String, query: List<Pair<String, String>>): HttpUrl {
        val parsed = "$baseUrl/api/v1/sdk/$endpoint".toHttpUrlOrNull()
            ?: throw DLNException(DLNErrorCode.BAD_REQUEST, serverMessage = "Invalid baseUrl")
        if (parsed.scheme != "https" && parsed.host !in DEV_HOSTS) {
            throw DLNException(DLNErrorCode.BAD_REQUEST, serverMessage = "baseUrl must use https")
        }
        val builder = parsed.newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    /**
     * One request with retries for transient failures (SDK-53): transport errors, timeouts,
     * 5xx and 429, up to `retryAttempts`, honouring Retry-After up to 5 s. Throws
     * [DLNException] (`network`) when the transport fails on the last attempt.
     */
    private suspend fun execute(
        method: String,
        endpoint: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val url = endpointUrl(endpoint, query)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey)
            .apply {
                if (method == "POST") post((body ?: "{}").toRequestBody(JSON))
            }
            .build()
        var attempt = 0
        while (true) {
            var failure: IOException? = null
            var response: HttpResponse? = null
            try {
                client.newCall(request).execute().use { r ->
                    val retryAfter = r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 }
                    response = HttpResponse(r.code, r.body?.string(), retryAfter)
                }
            } catch (e: IOException) {
                failure = e
            }
            val code = response?.code
            val transient = failure != null || (code != null && (code >= 500 || code == 429))
            if (!transient || attempt >= config.retryAttempts.coerceAtLeast(0)) {
                failure?.let { throw DLNException(DLNErrorCode.NETWORK, serverMessage = it.javaClass.simpleName, cause = it) }
                return@withContext response!!
            }
            val wait = response?.retryAfterMs?.coerceIn(0L, MAX_RETRY_AFTER_MS)
                ?: (retryBaseDelayMs * (1L shl attempt))
            log("$endpoint: transient failure (${code ?: failure?.javaClass?.simpleName}), retrying in ${wait}ms")
            delay(wait)
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun errorFor(response: HttpResponse): DLNException {
        val message = DLNJson.errorMessage(response.body)
        val code = when (response.code) {
            400 -> DLNErrorCode.BAD_REQUEST
            401 -> DLNErrorCode.INVALID_API_KEY
            403 -> if (message?.contains("credit", ignoreCase = true) == true) DLNErrorCode.NO_CREDITS else DLNErrorCode.ACCOUNT_INACTIVE
            429 -> DLNErrorCode.RATE_LIMITED
            in 500..599 -> DLNErrorCode.SERVER
            else -> DLNErrorCode.BAD_REQUEST
        }
        return DLNException(code, httpStatus = response.code, serverMessage = message)
    }

    // ---- Install Referrer (AND-12..19) ----

    internal sealed class ReferrerOutcome {
        data class Found(val data: ReferrerData, val link: DeeplinkMatch) : ReferrerOutcome()
        data class None(val data: ReferrerData?) : ReferrerOutcome()
        data class Transient(val error: DLNException?) : ReferrerOutcome()
    }

    private sealed class RawReferrer {
        data class Value(val referrer: String?) : RawReferrer()
        object Unsupported : RawReferrer()
        object Transient : RawReferrer()
    }

    /** Runs referrer processing once; re-runs it only after a transient outcome (AND-17). */
    internal suspend fun awaitReferrer(): ReferrerOutcome {
        val deferred = referrerMutex.withLock {
            val existing = referrerDeferred
            if (existing != null && !(existing.isCompleted && lastReferrerOutcome is ReferrerOutcome.Transient)) {
                existing
            } else {
                scope.async { processInstallReferrer().also { lastReferrerOutcome = it } }
                    .also { referrerDeferred = it }
            }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            ReferrerOutcome.Transient(null)
        } catch (e: Exception) {
            ReferrerOutcome.Transient(null)
        }
    }

    internal fun referrerOutcomeIsDefinitive(): Boolean {
        val outcome = lastReferrerOutcome
        return outcome != null && outcome !is ReferrerOutcome.Transient
    }

    private suspend fun processInstallReferrer(): ReferrerOutcome {
        return try {
            val state = loadState()
            state.referrerData?.let { cached ->
                log("referrer: using cached result")
                referrerData = cached
                return cached.deeplinkData?.let { ReferrerOutcome.Found(cached, it) } ?: ReferrerOutcome.None(cached)
            }
            if (state.referrerNone) {
                log("referrer: cached none")
                return ReferrerOutcome.None(null)
            }

            val raw = readReferrerWithRetry()
            val referrerString = when (raw) {
                RawReferrer.Unsupported -> {
                    log("referrer: Install Referrer not supported on this device")
                    saveReferrerNone()
                    return ReferrerOutcome.None(null)
                }
                RawReferrer.Transient -> {
                    log("referrer: service unavailable, will retry")
                    return ReferrerOutcome.Transient(null)
                }
                is RawReferrer.Value -> raw.referrer
            }

            val (fpId, deeplinkId) = DLNLinks.parseReferrer(referrerString)
            if (fpId == null && deeplinkId == null) {
                log("referrer: no DLN parameters (organic or other source)")
                saveReferrerNone()
                return ReferrerOutcome.None(null)
            }

            val query = if (fpId != null) "fp_id" to fpId else "deeplink_id" to deeplinkId!!
            log("referrer: GET /api/v1/sdk/referrer-lookup?${query.first}=…")
            val response = try {
                execute("GET", "referrer-lookup", listOf(query))
            } catch (e: DLNException) {
                log("referrer: lookup failed (${e.code.value}), will retry")
                return ReferrerOutcome.Transient(e)
            }

            fun definitive(link: DeeplinkMatch?): ReferrerOutcome {
                val data = ReferrerData(
                    referrerString = referrerString ?: "",
                    fpId = fpId,
                    deeplinkId = deeplinkId,
                    deeplinkData = link,
                    processedAt = System.currentTimeMillis(),
                )
                referrerData = data
                saveReferrerData(data)
                return if (link != null) ReferrerOutcome.Found(data, link) else ReferrerOutcome.None(data)
            }

            when {
                response.code in 200..299 -> {
                    val decoded = try {
                        DLNJson.decodeReferrerLookup(DLNJson.parse(response.body))
                    } catch (e: DLNJson.Invalid) {
                        log("referrer: malformed lookup response, will retry")
                        return ReferrerOutcome.Transient(DLNException(DLNErrorCode.INVALID_RESPONSE, httpStatus = response.code))
                    }
                    if (decoded.success && decoded.deeplink != null) {
                        log("referrer: found deep link ${decoded.deeplink.fullPath ?: decoded.deeplink.targetUrl}")
                        definitive(decoded.deeplink)
                    } else {
                        log("referrer: lookup returned no link (${decoded.message})")
                        definitive(null)
                    }
                }
                response.code == 400 || response.code == 404 || response.code == 410 -> {
                    log("referrer: lookup ${response.code}, no link")
                    definitive(null)
                }
                else -> {
                    val error = errorFor(response)
                    log("referrer: lookup failed (${error.code.value}), will retry")
                    ReferrerOutcome.Transient(error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Referrer processing failed: ${e.javaClass.simpleName}")
            ReferrerOutcome.Transient(null)
        }
    }

    private suspend fun readReferrerWithRetry(): RawReferrer {
        repeat(REFERRER_ATTEMPTS) { attempt ->
            val raw = readReferrerOnce()
            if (raw !is RawReferrer.Transient) return raw
            if (attempt < REFERRER_ATTEMPTS - 1) delay(referrerRetryDelayMs * (1L shl attempt))
        }
        return RawReferrer.Transient
    }

    /** Resumes exactly once and ends the connection in every path (AND-13). */
    private suspend fun readReferrerOnce(): RawReferrer {
        val referrerClient = try {
            InstallReferrerClient.newBuilder(appContext).build()
        } catch (e: Exception) {
            return RawReferrer.Unsupported
        }
        return suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            fun finish(result: RawReferrer) {
                if (!done.compareAndSet(false, true)) return
                try {
                    referrerClient.endConnection()
                } catch (e: Exception) {
                    // ignore
                }
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    try {
                        referrerClient.endConnection()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            try {
                referrerClient.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val value = try {
                                    referrerClient.installReferrer?.installReferrer
                                } catch (e: Exception) {
                                    finish(RawReferrer.Transient)
                                    return
                                }
                                finish(RawReferrer.Value(value))
                            }
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                                finish(RawReferrer.Unsupported)
                            else -> finish(RawReferrer.Transient)
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        finish(RawReferrer.Transient)
                    }
                })
            } catch (e: SecurityException) {
                // Some ROMs forbid binding to the Play Store service: treat like "not supported".
                finish(RawReferrer.Unsupported)
            } catch (e: Exception) {
                finish(RawReferrer.Transient)
            }
        }
    }

    // ---- Local state (SDK-23..26, SDK-65, AND-22) ----

    private data class State(
        val deferredChecked: Boolean = false,
        val referrerData: ReferrerData? = null,
        val referrerNone: Boolean = false,
    )

    private fun readStateJson(): JsonObject {
        return try {
            if (!stateFile.exists()) return JsonObject()
            val element = JsonParser.parseString(stateFile.readText())
            if (element.isJsonObject) element.asJsonObject else JsonObject()
        } catch (e: Exception) {
            JsonObject()
        }
    }

    private fun writeStateJson(o: JsonObject) {
        try {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(o.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            warn("Could not persist SDK state: ${e.javaClass.simpleName}")
        }
    }

    private fun updateState(block: (JsonObject) -> Unit) {
        synchronized(stateLock) {
            migrateLegacyPrefs()
            val o = readStateJson()
            block(o)
            writeStateJson(o)
        }
    }

    private fun loadState(): State = synchronized(stateLock) {
        migrateLegacyPrefs()
        val o = readStateJson()
        fun flag(key: String) = o.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
        State(
            deferredChecked = flag("deferred_checked"),
            referrerData = DLNJson.decodeReferrerData(o.get("referrer_data")),
            referrerNone = flag("referrer_none"),
        )
    }

    /**
     * 1.0 stored the referrer result in SharedPreferences("dln_referrer"), which Auto Backup
     * restores. An install that already ran 1.0 is past its first launch, so the result is
     * moved into the no-backup state as "already checked" and the old prefs are deleted.
     */
    private fun migrateLegacyPrefs() {
        if (legacyMigrated) return
        legacyMigrated = true
        try {
            val prefs = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains("referrer_data")) return
            val legacy = try {
                DLNJson.decodeReferrerData(JsonParser.parseString(prefs.getString("referrer_data", null) ?: "null"))
            } catch (e: Exception) {
                null
            }
            val o = readStateJson()
            o.addProperty("deferred_checked", true)
            if (legacy != null && (legacy.fpId != null || legacy.deeplinkId != null)) {
                o.add("referrer_data", DLNJson.encodeReferrerData(legacy))
                o.addProperty("processed_at", legacy.processedAt)
            }
            writeStateJson(o)
            prefs.edit().clear().commit()
            appContext.deleteSharedPreferences(LEGACY_PREFS)
            log("Migrated 1.0 referrer cache")
        } catch (e: Exception) {
            // best effort
        }
    }

    private fun saveReferrerData(data: ReferrerData) = updateState {
        it.add("referrer_data", DLNJson.encodeReferrerData(data))
        it.addProperty("processed_at", data.processedAt)
        it.remove("referrer_none")
    }

    private fun saveReferrerNone() = updateState {
        it.addProperty("referrer_none", true)
        it.addProperty("processed_at", System.currentTimeMillis())
    }

    private fun markDeferredChecked() = updateState { it.addProperty("deferred_checked", true) }

    /** SDK-26: clears the flag and the referrer cache, in memory and on disk. */
    internal fun resetLocalState() {
        synchronized(stateLock) {
            legacyMigrated = true
            try {
                stateFile.delete()
            } catch (e: Exception) {
                // ignore
            }
            clearInitCache()
        }
        referrerData = null
        lastReferrerOutcome = null
        referrerDeferred = null
        log("Deferred deep link state reset")
    }

    internal fun resetLocalStateForTesting() = resetLocalState()

    // ---- checkDeferredDeepLink (SDK-10..27) ----

    internal suspend fun checkDeferred(minConfidence: Double?, force: Boolean): Result<DeferredDeepLink?> {
        val key = minConfidence to force
        val deferred = synchronized(checkLock) {
            inFlightChecks[key]?.takeIf { it.isActive }
                ?: scope.async { runCheck(minConfidence, force) }.also { inFlightChecks[key] = it }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = e))
        } catch (e: Exception) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
        } finally {
            synchronized(checkLock) { if (inFlightChecks[key] === deferred) inFlightChecks.remove(key) }
        }
    }

    private suspend fun runCheck(minConfidence: Double?, force: Boolean): Result<DeferredDeepLink?> {
        val init = awaitInit()
        if (init.isFailure) {
            log("checkDeferredDeepLink: not initialized")
            return Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = init.exceptionOrNull()))
        }
        if (!force && loadState().deferredChecked) {
            log("checkDeferredDeepLink: already completed on an earlier launch, no link")
            return Result.success(null)
        }

        // 1. Play Install Referrer (exact)
        var definitive = true
        when (val referrer = awaitReferrer()) {
            is ReferrerOutcome.Found -> {
                val link = fromServer(referrer.link, DeferredDeepLinkSource.REFERRER, 100.0, MatchQuality.HIGH, null)
                log("checkDeferredDeepLink: referrer found ${link.fullPath} (confidence 100, HIGH)")
                markDeferredChecked()
                return Result.success(link)
            }
            is ReferrerOutcome.None -> log("checkDeferredDeepLink: referrer none")
            is ReferrerOutcome.Transient -> {
                definitive = false
                log("checkDeferredDeepLink: referrer error (${referrer.error?.code?.value ?: "unavailable"}), continuing")
            }
        }

        // 2. Fingerprint (scored, best effort)
        log("checkDeferredDeepLink: trying fingerprint match")
        val matches = requestMatches()
        val response = matches.getOrElse {
            log("checkDeferredDeepLink: fingerprint error (${(it as? DLNException)?.code?.value})")
            return Result.failure(it)
        }
        val candidate = response.matches.firstOrNull { m ->
            m.deeplink != null && (minConfidence == null || m.confidenceScore >= minConfidence)
        }
        if (candidate != null) {
            val quality = MatchQuality.parse(candidate.matchQuality) ?: MatchQuality.fromScore(candidate.confidenceScore)
            val link = fromServer(candidate.deeplink!!, DeferredDeepLinkSource.FINGERPRINT, candidate.confidenceScore, quality, candidate.matchDetails)
            log("checkDeferredDeepLink: fingerprint found ${link.fullPath} (confidence ${candidate.confidenceScore}, $quality)")
            markDeferredChecked()
            return Result.success(link)
        }
        log("checkDeferredDeepLink: fingerprint found no eligible candidate (${response.matches.size} returned)")
        if (definitive) markDeferredChecked()
        return Result.success(null)
    }

    private fun fromServer(
        d: DeeplinkMatch,
        source: DeferredDeepLinkSource,
        score: Double,
        quality: MatchQuality,
        details: MatchDetails?,
    ): DeferredDeepLink {
        val fullPath = d.fullPath?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }
            ?: d.targetUrl.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }
            ?: "/"
        val queryParams: Map<String, QueryValue> =
            if (d.queryParams != null) d.typedQueryParams else DLNLinks.parseQuery(DLNLinks.queryFromFullPath(fullPath))
        val absoluteTarget = d.targetUrl.takeIf {
            val uri = try { Uri.parse(it) } catch (e: Exception) { null }
            val scheme = uri?.scheme?.lowercase(Locale.ROOT)
            (scheme == "https" || scheme == "http") && hostAllowed(uri?.host)
        }
        val url = absoluteTarget ?: "https://${primaryDomain ?: DLNLinks.DLN_APEX}${fullPath.substringBefore('#')}"
        return DeferredDeepLink(
            path = DLNLinks.pathFromFullPath(fullPath),
            queryParams = queryParams,
            fullPath = fullPath,
            url = url,
            source = source,
            confidenceScore = score,
            matchQuality = quality,
            deeplinkId = d.id,
            targetUrl = d.targetUrl,
            campaignId = d.campaignId,
            metadata = d.metadata,
            matchedAt = d.matchedAt.takeIf { it.isNotEmpty() },
            expiresAt = d.expiresAt.takeIf { it.isNotEmpty() },
            matchDetails = details,
        )
    }

    // ---- Fingerprint (AND-20, AND-21, AND-23) ----

    private fun simpleHash(str: String): String {
        var hash = 0
        for (element in str) {
            hash = ((hash shl 5) - hash) + element.code
        }
        return hash.toString(16)
    }

    private fun generateHardwareFingerprint(
        platform: String,
        screenWidth: Int?,
        screenHeight: Int?,
        pixelRatio: Float?,
        language: String,
        timezone: String
    ): String {
        val components = listOfNotNull(
            platform,
            Build.VERSION.RELEASE,
            screenWidth?.toString(),
            screenHeight?.toString(),
            pixelRatio?.toString(),
            language,
            timezone
        )
        return simpleHash(components.joinToString("|"))
    }

    private fun formatDateToISO8601(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
    }

    private fun androidId(): String? = try {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    internal fun getFingerprint(): Fingerprint {
        val screenWidth: Int?
        val screenHeight: Int?
        val pixelRatio: Float?
        if (config.overrideScreenWidth != null && config.overrideScreenHeight != null) {
            screenWidth = config.overrideScreenWidth
            screenHeight = config.overrideScreenHeight
            pixelRatio = config.overridePixelRatio ?: safeDensity()
        } else {
            val metrics = try {
                appContext.resources.displayMetrics
            } catch (e: Exception) {
                null
            }
            val density = metrics?.density?.takeIf { it > 0f } ?: 1f
            // Logical size in dp, like CSS pixels on the link page (AND-23, SDK-22)
            screenWidth = metrics?.let { Math.round(it.widthPixels / density) }
            screenHeight = metrics?.let { Math.round(it.heightPixels / density) }
            pixelRatio = metrics?.let { density }
        }
        val language = Locale.getDefault().toLanguageTag()
        val timezone = TimeZone.getDefault().id
        return Fingerprint(
            userAgent = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: "",
            deviceModel = Build.MODEL ?: "",
            language = language,
            timezone = timezone,
            installedAt = installTime,
            lastOpenedAt = formatDateToISO8601(Date()),
            deviceId = androidId(),
            advertisingId = null,
            vendorId = null,
            hardwareFingerprint = generateHardwareFingerprint("android", screenWidth, screenHeight, pixelRatio, language, timezone),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            pixelRatio = pixelRatio,
        )
    }

    private fun safeDensity(): Float = try {
        appContext.resources.displayMetrics.density
    } catch (e: Exception) {
        1f
    }

    /** The match body with every nullable key present (AND-20, SDK-21). */
    private fun matchBody(fp: Fingerprint): String {
        fun JsonObject.put(key: String, value: String?) =
            if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
        val o = JsonObject()
        o.addProperty("user_agent", fp.userAgent)
        o.addProperty("platform", fp.platform)
        o.addProperty("os_version", fp.osVersion)
        o.addProperty("device_model", fp.deviceModel)
        o.addProperty("language", fp.language)
        o.addProperty("timezone", fp.timezone)
        o.addProperty("installed_at", fp.installedAt)
        o.addProperty("last_opened_at", fp.lastOpenedAt)
        o.put("device_id", fp.deviceId)
        o.put("advertising_id", null)
        o.put("vendor_id", null)
        o.put("hardware_fingerprint", fp.hardwareFingerprint)
        fp.screenWidth?.let { o.addProperty("screen_width", it) }
        fp.screenHeight?.let { o.addProperty("screen_height", it) }
        fp.pixelRatio?.let { o.addProperty("pixel_ratio", it) }
        return JsonObject().apply { add("fingerprint", o) }.toString()
    }

    private suspend fun requestMatches(): Result<MatchResponse> {
        return try {
            val body = matchBody(getFingerprint())
            log("fingerprint: POST /api/v1/sdk/match")
            val response = execute("POST", "match", body = body)
            if (response.code !in 200..299) {
                Result.failure(errorFor(response))
            } else {
                val parsed = DLNJson.decodeMatchResponse(DLNJson.parse(response.body))
                val best = parsed.matches.firstOrNull()
                log(
                    "fingerprint: ${parsed.matches.size} candidate(s)" +
                        (best?.let { ", best confidence ${it.confidenceScore} (${it.matchQuality ?: MatchQuality.fromScore(it.confidenceScore)})" } ?: "")
                )
                Result.success(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DLNException) {
            Result.failure(e)
        } catch (e: DLNJson.Invalid) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, httpStatus = 200, serverMessage = e.message))
        } catch (e: Exception) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
        }
    }

    /**
     * All fingerprint candidates from `/sdk/match`, unfiltered and in server order (SDK-20).
     * Waits for init; reports `notInitialized` after a failed init. Never throws.
     */
    suspend fun findDeferredUserResult(): Result<MatchResponse> {
        return try {
            val init = awaitInit()
            if (init.isFailure) {
                return Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = init.exceptionOrNull()))
            }
            requestMatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
        }
    }

    /**
     * 1.0 API: fingerprint candidates, or null on any error. Never throws (malformed JSON
     * included). Prefer [findDeferredUserResult] (typed errors) or [DLN.checkDeferredDeepLink].
     */
    suspend fun findDeferredUser(): MatchResponse? {
        log("Finding deferred user...")
        return try {
            requestMatches().getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---- Domains and parsing (SDK-30..38, AND-25..27) ----

    internal fun hostAllowed(host: String?): Boolean {
        val domains = linkDomains
        return when {
            domains == null -> DLNLinks.isAllowedHost(host, null)
            aliasUnknown -> DLNLinks.isAllowedHost(host, domains) || DLNLinks.isAllowedHost(host, null)
            else -> DLNLinks.isAllowedHost(host, domains)
        }
    }

    private fun currentDomainsForParse(): Set<String>? {
        val domains = linkDomains ?: return null
        return if (aliasUnknown) null else domains
    }

    /** SDK-31 host rule: exact link domains after init, provisional `*.deeplinknow.com` before. */
    fun isValidDomain(domain: String): Boolean {
        val ok = try {
            hostAllowed(domain)
        } catch (e: Exception) {
            false
        }
        if (!ok) log("Domain is not one of this app's link domains")
        return ok
    }

    /** SDK-30..36 parsing on this instance. */
    internal fun parseLink(url: String): ParsedDeepLink? {
        val parsed = DLNLinks.parse(url, currentDomainsForParse()) ?: return null
        return if (aliasUnknown && !hostAllowed(Uri.parse(parsed.url).host)) null else parsed
    }

    /**
     * 1.0 API, kept with its 1.0 semantics (decoded path, `""` for the root, first value of a
     * repeated key, `""` for flags). The host rule is the strict SDK-31 rule.
     */
    @Deprecated(
        "Use DLN.parseDeepLink(url), which returns ParsedDeepLink (undecoded path, typed query values).",
        ReplaceWith("DLN.parseDeepLink(url)", "com.deeplinknow.DLN"),
    )
    fun parseDeepLink(url: String): Pair<String, Map<String, String>>? {
        return try {
            val parsed = parseLink(url) ?: return null
            val uri = Uri.parse(parsed.url)
            Pair(uri.path ?: "", parsed.queryParams.mapValues { it.value.firstValue() })
        } catch (e: Exception) {
            null
        }
    }

    private fun setDeepLinkCallbackInternal(callback: DeepLinkCallback?) {
        deepLinkCallback = callback
        log("Deep link callback ${if (callback != null) "registered" else "removed"}")
    }

    private fun handleDeepLinkInternal(url: String): Boolean {
        return try {
            val parsed = parseLink(url)
            if (parsed == null) {
                log("handleDeepLink: not a link on this app's domains")
                return false
            }
            val route = (Uri.parse(parsed.url).path ?: "").removePrefix("/")
            val data = DeepLinkData(
                route = route,
                params = parsed.queryParams.mapValues { it.value.firstValue() },
                path = parsed.path,
                queryParams = parsed.queryParams,
            )
            log("handleDeepLink: ${parsed.fullPath}")
            deepLinkCallback?.let { callback ->
                try {
                    callback.onDeepLinkOpen(data)
                } catch (e: Exception) {
                    warn("Deep link callback threw ${e.javaClass.simpleName}")
                }
            } ?: log("No deep link callback registered")
            true
        } catch (e: Exception) {
            warn("Error handling deep link: ${e.javaClass.simpleName}")
            false
        }
    }

    // ---- Clipboard (AND-28, AND-29, SDK-40..45) ----

    private fun clipboardManager(): ClipboardManager? = try {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    } catch (e: Exception) {
        null
    }

    /**
     * Cheap "is there probably a link on the clipboard" check. Reads only the clip description
     * (no "pasted from your clipboard" toast on Android 12+). `true` means "worth calling
     * [checkClipboard]", not "verified".
     */
    suspend fun hasDeepLinkToken(): Boolean {
        return try {
            val cm = clipboardManager() ?: return false
            if (!cm.hasPrimaryClip()) return false
            val description = cm.primaryClipDescription ?: return false
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the clipboard text if, trimmed, it is exactly one http(s) URL on this app's link
     * domains (SDK-40). Null otherwise, including when the app has no focus (Android 10+).
     * Never writes to or clears the clipboard.
     */
    suspend fun checkClipboard(): String? {
        return try {
            val cm = clipboardManager() ?: return null
            clipboardRead = true
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0)?.text?.toString()?.trim()
            if (text.isNullOrEmpty() || text.any { it.isWhitespace() }) return null
            if (parseLink(text) == null) return null
            log("clipboard: found a DLN link")
            text
        } catch (e: Exception) {
            null
        }
    }

    // ---- Referrer accessors (1.0) ----

    /**
     * The cached, definitive Install Referrer result (unchanged 1.0 meaning: returned on every
     * launch). Use [DLN.checkDeferredDeepLink] for first-launch delivery.
     */
    fun getDeferredDeepLink(): DeeplinkMatch? {
        if (referrerData == null) referrerData = loadState().referrerData
        return referrerData?.deeplinkData
    }

    /** The cached raw Install Referrer result (`fp_id` / `deeplink_id`), or null. */
    fun getReferrerData(): ReferrerData? {
        if (referrerData == null) referrerData = loadState().referrerData
        return referrerData
    }

    companion object {
        /** SDK version, kept in sync with build.gradle.kts (AND-01). */
        const val SDK_VERSION = "1.1.0"

        private const val TAG = "DLN"
        private const val INIT_CACHE_FILE = "com.deeplinknow.initcache.json"
        private const val INIT_CACHE_FORMAT = 1
        private const val INIT_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** SDK-83: whether this process already had its one chance to use the init cache. */
        @Volatile
        internal var initCacheConsulted = false
        private const val LEGACY_PREFS = "dln_referrer"
        private const val REFERRER_ATTEMPTS = 3
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private val JSON = "application/json".toMediaType()
        private val DEV_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /** Strong process-wide singleton holding only the application context (AND-05). */
        @Volatile
        internal var instance: DLN? = null

        @Volatile
        private var pendingCallback: DeepLinkCallback? = null

        private val lock = Any()

        /** For callback variants when no instance exists. */
        private val sdkScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "Unexpected internal error: ${e.javaClass.simpleName}")
            }
        )

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        private fun postMain(block: () -> Unit) {
            mainHandler.post {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "App callback threw ${e.javaClass.simpleName}")
                }
            }
        }

        private fun notInitialized() = DLNException(DLNErrorCode.NOT_INITIALIZED)

        /**
         * Starts the SDK. Returns immediately (AND-05): init and Install Referrer processing run
         * in the background. Calling again with the same key reuses the running/completed init
         * (unless it failed); a different key replaces the previous state (SDK-07).
         *
         * @param onComplete called once on the main thread with the init outcome (AND-06).
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            context: Context,
            apiKey: String,
            enableLogs: Boolean = false,
            overrideScreenWidth: Int? = null,
            overrideScreenHeight: Int? = null,
            overridePixelRatio: Float? = null,
            baseUrl: String = DLNConfig.DEFAULT_BASE_URL,
            timeoutMs: Long = DLNConfig.DEFAULT_TIMEOUT_MS,
            retryAttempts: Int = DLNConfig.DEFAULT_RETRY_ATTEMPTS,
            onComplete: ((Result<DLNAppInfo>) -> Unit)? = null,
        ) {
            try {
                val dln = synchronized(lock) {
                    val existing = instance
                    if (existing != null && existing.config.apiKey == apiKey && !existing.initFailed) {
                        existing
                    } else {
                        existing?.shutdown()
                        DLN(
                            DLNConfig(
                                apiKey = apiKey,
                                enableLogs = enableLogs,
                                overrideScreenWidth = overrideScreenWidth,
                                overrideScreenHeight = overrideScreenHeight,
                                overridePixelRatio = overridePixelRatio,
                                baseUrl = baseUrl,
                                timeoutMs = timeoutMs,
                                retryAttempts = retryAttempts,
                            ),
                            context.applicationContext ?: context,
                        ).also { instance = it }
                    }
                }
                dln.start()
                if (onComplete != null) {
                    dln.scope.launch {
                        val result = dln.awaitInit()
                        postMain { onComplete(result) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DLN.init failed: ${e.javaClass.simpleName}")
                onComplete?.let { cb -> postMain { cb(Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = e))) } }
            }
        }

        /** Waits for the init started by [init]. `notInitialized` when [init] was never called. */
        @JvmStatic
        suspend fun awaitInitialization(): Result<DLNAppInfo> =
            instance?.awaitInit() ?: Result.failure(notInitialized())

        /** True once an init completed successfully (SDK-05). */
        @JvmStatic
        val isInitialized: Boolean
            get() = instance?.isInitializedSuccessfully == true

        /**
         * The one call (SDK-10): Install Referrer first, then fingerprint matching. Resolves to a
         * [DeferredDeepLink], `null` (no link, or already delivered on an earlier launch), or a
         * [DLNException] failure. Never throws.
         *
         * @param minConfidence optional threshold for fingerprint candidates (off by default).
         * @param force ignore the first-launch flag.
         */
        suspend fun checkDeferredDeepLink(
            minConfidence: Double? = null,
            force: Boolean = false,
        ): Result<DeferredDeepLink?> {
            return try {
                instance?.checkDeferred(minConfidence, force) ?: Result.failure(notInitialized())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
            }
        }

        /** Callback variant of [checkDeferredDeepLink]; [callback] runs once on the main thread. */
        @JvmStatic
        @JvmOverloads
        fun checkDeferredDeepLink(
            minConfidence: Double? = null,
            force: Boolean = false,
            callback: (Result<DeferredDeepLink?>) -> Unit,
        ) {
            val dln = instance
            (dln?.scope ?: sdkScope).launch {
                val result = checkDeferredDeepLink(minConfidence, force)
                postMain { callback(result) }
            }
        }

        /** Java-friendly callback variant of [checkDeferredDeepLink]. */
        @JvmStatic
        @JvmOverloads
        fun checkDeferredDeepLink(
            minConfidence: Double? = null,
            force: Boolean = false,
            callback: DeferredDeepLinkCallback,
        ) {
            checkDeferredDeepLink(minConfidence, force) { result: Result<DeferredDeepLink?> ->
                callback.onResult(result.getOrNull(), result.exceptionOrNull() as? DLNException
                    ?: result.exceptionOrNull()?.let { DLNException(DLNErrorCode.INVALID_RESPONSE, cause = it) })
            }
        }

        /** Clears the first-launch flag and the cached referrer result (SDK-26). For testing. */
        @JvmStatic
        fun resetDeferredDeepLink() {
            try {
                instance?.resetLocalState()
            } catch (e: Exception) {
                Log.w(TAG, "resetDeferredDeepLink failed: ${e.javaClass.simpleName}")
            }
        }

        /** Static form of [DLN.findDeferredUserResult]; `notInitialized` before [init]. */
        suspend fun findDeferredUserResult(): Result<MatchResponse> =
            instance?.findDeferredUserResult() ?: Result.failure(notInitialized())

        /**
         * Parses a link on the app's domains (SDK-30): `{path, queryParams, fullPath, url}` or
         * null. Works before init with the provisional `*.deeplinknow.com` rule (SDK-37).
         */
        fun parseDeepLink(url: String): ParsedDeepLink? = try {
            instance?.parseLink(url) ?: if (instance == null) DLNLinks.parse(url, null) else null
        } catch (e: Exception) {
            null
        }

        /** The SDK-31 host rule. */
        fun isValidDomain(host: String): Boolean = try {
            instance?.hostAllowed(host) ?: DLNLinks.isAllowedHost(host, null)
        } catch (e: Exception) {
            false
        }

        @JvmStatic
        fun getInstance(): DLN {
            return instance ?: throw IllegalStateException("DLN not initialized")
        }

        @JvmStatic
        fun setDeepLinkCallback(callback: DeepLinkCallback?) {
            val dln = instance
            if (dln != null) dln.setDeepLinkCallbackInternal(callback) else pendingCallback = callback
        }

        @JvmStatic
        fun handleDeepLink(url: String): Boolean {
            val dln = instance ?: return false
            return dln.handleDeepLinkInternal(url)
        }

        internal fun resetForTesting() {
            synchronized(lock) {
                instance?.shutdown()
                instance = null
                pendingCallback = null
                initCacheConsulted = false
            }
        }
    }
}
