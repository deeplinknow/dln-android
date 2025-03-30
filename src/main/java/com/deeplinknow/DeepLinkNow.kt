package com.deeplinknow

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.lang.ref.WeakReference

data class DLNConfig(
    val apiKey: String,
    val enableLogs: Boolean = false
)

data class Fingerprint(
    @SerializedName("user_agent") val userAgent: String,
    val platform: String = "android", // Must be "ios" or "android"
    @SerializedName("os_version") val osVersion: String,
    @SerializedName("device_model") val deviceModel: String,
    val language: String,
    val timezone: String,
    @SerializedName("installed_at") val installedAt: String, // RFC3339 format: yyyy-MM-dd'T'HH:mm:ss'Z'
    @SerializedName("last_opened_at") val lastOpenedAt: String, // RFC3339 format: yyyy-MM-dd'T'HH:mm:ss'Z'
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("advertising_id") val advertisingId: String, // Required field
    @SerializedName("vendor_id") val vendorId: String, // Required field
    @SerializedName("hardware_fingerprint") val hardwareFingerprint: String?,
    @SerializedName("screen_width") val screenWidth: Int?,
    @SerializedName("screen_height") val screenHeight: Int?,
    @SerializedName("pixel_ratio") val pixelRatio: Float?
)

data class DeeplinkMatch(
    val id: String,
    @SerializedName("target_url") val targetUrl: String,
    val metadata: Map<String, Any>,
    @SerializedName("campaign_id") val campaignId: String?,
    @SerializedName("matched_at") val matchedAt: String,
    @SerializedName("expires_at") val expiresAt: String
)

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
    @SerializedName("match_details") val matchDetails: MatchDetails
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
        @SerializedName("custom_domains") val customDomains: List<CustomDomain>
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

class DLN private constructor(
    private val config: DLNConfig,
    private val context: Context
) {
    private val gson = Gson()
    private val client = OkHttpClient()
    private val validDomains = mutableSetOf("deeplinknow.com", "deeplink.now")
    private val installTime = formatDateToISO8601(Instant.now())
    private var initResponse: InitResponse? = null

    private fun log(message: String, vararg args: Any?) {
        if (config.enableLogs) {
            Log.d("DLN", "$message ${args.joinToString()}")
        }
    }

    private fun warn(message: String, vararg args: Any?) {
        Log.w("DLN", "$message ${args.joinToString()}")
    }

    private suspend fun makeRequest(
        endpoint: String,
        method: String = "GET",
        body: Any? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://deeplinknow.com/api/v1/sdk/$endpoint")
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey)
                .apply {
                    when (method) {
                        "POST" -> {
                            val jsonBody = gson.toJson(body)
                            post(jsonBody.toRequestBody("application/json".toMediaType()))
                        }
                        "PUT" -> {
                            val jsonBody = gson.toJson(body)
                            put(jsonBody.toRequestBody("application/json".toMediaType()))
                        }
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    warn("API request failed: ${response.code} ${response.body?.string()}")
                    return@withContext null
                }
                return@withContext response.body?.string()
            }
        } catch (e: Exception) {
            warn("API request failed", e)
            return@withContext null
        }
    }

    private fun simpleHash(str: String): String {
        var hash = 0
        for (element in str) {
            val char = element.code
            hash = ((hash shl 5) - hash) + char
            hash = hash and hash // Convert to 32bit integer
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
        val components = listOf(
            platform,
            Build.VERSION.RELEASE,
            screenWidth?.toString(),
            screenHeight?.toString(),
            pixelRatio?.toString(),
            language,
            timezone
        ).filterNotNull()

        val fingerprintString = components.joinToString("|")
        return simpleHash(fingerprintString)
    }

    private fun formatDateToISO8601(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
        return formatter.format(instant)
    }

    private suspend fun getFingerprint(): Fingerprint {
        val currentTime = formatDateToISO8601(Instant.now())
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val pixelRatio = displayMetrics.density

        val hardwareFingerprint = generateHardwareFingerprint(
            platform = "android",
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            pixelRatio = pixelRatio,
            language = Locale.getDefault().language,
            timezone = TimeZone.getDefault().id
        )

        return Fingerprint(
            userAgent = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
            platform = "android",
            osVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
            language = Locale.getDefault().language,
            timezone = TimeZone.getDefault().id,
            installedAt = installTime,
            lastOpenedAt = currentTime,
            deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
            advertisingId = "",
            vendorId = "",
            hardwareFingerprint = hardwareFingerprint,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            pixelRatio = pixelRatio
        )
    }

    suspend fun initializeSDK() {
        log("Initializing with config:", config)

        val response = makeRequest(
            endpoint = "init",
            method = "POST",
            body = mapOf("api_key" to config.apiKey)
        )?.let { gson.fromJson(it, InitResponse::class.java) }

        if (response != null) {
            initResponse = response
            response.app.customDomains
                .filter { it.domain != null && it.verified == true }
                .forEach { domain ->
                    domain.domain?.let { validDomains.add(it) }
                }
            log("Init response:", response)
            log("Valid domains:", validDomains)
        }
    }

    fun isValidDomain(domain: String): Boolean {
        return validDomains.contains(domain)
    }

    fun parseDeepLink(url: String): Pair<String, Map<String, String>>? {
        return try {
            val uri = Uri.parse(url)
            if (!isValidDomain(uri.host ?: "")) {
                return null
            }

            val parameters = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    parameters[key] = value
                }
            }

            Pair(uri.path ?: "", parameters)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun findDeferredUser(): MatchResponse? {
        log("Finding deferred user...")

        val fingerprint = getFingerprint()
        val matchRequest = mapOf("fingerprint" to fingerprint)

        log("Sending match request:", matchRequest)

        return makeRequest(
            endpoint = "match",
            method = "POST",
            body = matchRequest
        )?.let { gson.fromJson(it, MatchResponse::class.java) }?.also {
            log("Match response:", it)
        }
    }

    suspend fun hasDeepLinkToken(): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = clipData.getItemAt(0).text?.toString()
                content?.startsWith("dln://") ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            warn("Failed to check clipboard:", e)
            false
        }
    }

    suspend fun checkClipboard(): String? {
        if (!isInitialized()) {
            warn("SDK not initialized. Call initialize() first")
            return null
        }

        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val content = clipData.getItemAt(0).text?.toString()
                val domain = content?.split("://")?.getOrNull(1)?.split("/")?.getOrNull(0)
                if (domain != null && (domain.contains("deeplinknow.com") || 
                    domain.contains("deeplink.now") || 
                    isValidDomain(domain))) {
                    log("Found deep link token in clipboard")
                    content
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            warn("Failed to read clipboard:", e)
            null
        }
    }

    private fun isInitialized(): Boolean {
        return instance?.get() != null
    }

    companion object {
        // Use a WeakReference to prevent memory leaks
        private var instance: WeakReference<DLN>? = null

        @JvmStatic
        @JvmOverloads
        fun init(
            context: Context,
            apiKey: String,
            enableLogs: Boolean = false
        ) {
            instance = WeakReference(
                DLN(
                    config = DLNConfig(
                        apiKey = apiKey,
                        enableLogs = enableLogs
                    ),
                    context = context.applicationContext
                )
            )
            
            // Asynchronously initialize and fetch custom domains
            kotlinx.coroutines.GlobalScope.launch {
                instance?.get()?.initializeSDK()
            }
        }

        @JvmStatic
        fun getInstance(): DLN {
            return instance?.get() ?: throw IllegalStateException("DLN not initialized")
        }
    }
}