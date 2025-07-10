package com.deeplinknow

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume

data class DLNConfig(
    val apiKey: String,
    val enableLogs: Boolean = false,
    val overrideScreenWidth: Int? = null,
    val overrideScreenHeight: Int? = null,
    val overridePixelRatio: Float? = null
)

data class DeepLinkData(
    val route: String,
    val params: Map<String, String>
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

class DLN private constructor(
    private val config: DLNConfig,
    private val context: Context
) {
    private val gson = Gson()
    private val client = OkHttpClient()
    private val validDomains = mutableSetOf("deeplinknow.com", "deeplink.now")
    private val installTime = formatDateToISO8601(Instant.now())
    private var initResponse: InitResponse? = null
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("dln_referrer", Context.MODE_PRIVATE)
    private var referrerData: ReferrerData? = null
    private var deepLinkCallback: DeepLinkCallback? = null

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

    private suspend fun fetchInstallReferrer(): String? = suspendCancellableCoroutine { continuation ->
        val referrerClient = InstallReferrerClient.newBuilder(context).build()
        
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        try {
                            val response = referrerClient.installReferrer
                            val referrerUrl = response.installReferrer
                            log("Install referrer: $referrerUrl")
                            continuation.resume(referrerUrl)
                        } catch (e: Exception) {
                            warn("Failed to get install referrer", e)
                            continuation.resume(null)
                        } finally {
                            referrerClient.endConnection()
                        }
                    }
                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                        log("Install referrer not supported")
                        continuation.resume(null)
                        referrerClient.endConnection()
                    }
                    InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                        log("Install referrer service unavailable")
                        continuation.resume(null)
                        referrerClient.endConnection()
                    }
                    else -> {
                        log("Install referrer unknown response: $responseCode")
                        continuation.resume(null)
                        referrerClient.endConnection()
                    }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                log("Install referrer service disconnected")
                continuation.resume(null)
            }
        })
        
        continuation.invokeOnCancellation {
            referrerClient.endConnection()
        }
    }

    private fun parseReferrerString(referrerString: String): Pair<String?, String?> {
        log("Parsing referrer string: $referrerString")
        
        // Look for fp_id=xxxxx or deeplink_id=xxxxx
        val fpIdRegex = Regex("fp_id=([^&]+)")
        val deeplinkIdRegex = Regex("deeplink_id=([^&]+)")
        
        val fpIdMatch = fpIdRegex.find(referrerString)
        val deeplinkIdMatch = deeplinkIdRegex.find(referrerString)
        
        val fpId = fpIdMatch?.groupValues?.get(1)
        val deeplinkId = deeplinkIdMatch?.groupValues?.get(1)
        
        log("Parsed fpId: $fpId, deeplinkId: $deeplinkId")
        
        return Pair(fpId, deeplinkId)
    }

    private suspend fun lookupReferrerData(fpId: String?, deeplinkId: String?): DeeplinkMatch? {
        if (fpId == null && deeplinkId == null) {
            return null
        }
        
        val queryParam = if (fpId != null) "fp_id=$fpId" else "deeplink_id=$deeplinkId"
        val endpoint = "referrer-lookup?$queryParam"
        
        log("Looking up referrer data with endpoint: $endpoint")
        
        return makeRequest(endpoint)?.let { response ->
            try {
                val lookupResponse = gson.fromJson(response, ReferrerLookupResponse::class.java)
                if (lookupResponse.success) {
                    log("Referrer lookup successful: ${lookupResponse.deeplink}")
                    lookupResponse.deeplink
                } else {
                    log("Referrer lookup failed: ${lookupResponse.message}")
                    null
                }
            } catch (e: Exception) {
                warn("Failed to parse referrer lookup response", e)
                null
            }
        }
    }

    private fun cacheReferrerData(referrerData: ReferrerData) {
        try {
            val json = gson.toJson(referrerData)
            sharedPreferences.edit()
                .putString("referrer_data", json)
                .putLong("processed_at", System.currentTimeMillis())
                .apply()
            log("Cached referrer data")
        } catch (e: Exception) {
            warn("Failed to cache referrer data", e)
        }
    }

    private fun getCachedReferrerData(): ReferrerData? {
        return try {
            val json = sharedPreferences.getString("referrer_data", null)
            if (json != null) {
                val data = gson.fromJson(json, ReferrerData::class.java)
                log("Retrieved cached referrer data: $data")
                data
            } else {
                log("No cached referrer data found")
                null
            }
        } catch (e: Exception) {
            warn("Failed to retrieve cached referrer data", e)
            null
        }
    }

    private fun hasProcessedReferrer(): Boolean {
        return sharedPreferences.contains("referrer_data")
    }

    private suspend fun processInstallReferrer() {
        if (hasProcessedReferrer()) {
            log("Install referrer already processed")
            referrerData = getCachedReferrerData()
            return
        }
        
        log("Processing install referrer...")
        
        val referrerString = fetchInstallReferrer()
        if (referrerString.isNullOrEmpty()) {
            log("No install referrer found")
            return
        }
        
        val (fpId, deeplinkId) = parseReferrerString(referrerString)
        if (fpId == null && deeplinkId == null) {
            log("No valid referrer parameters found")
            return
        }
        
        val deeplinkData = lookupReferrerData(fpId, deeplinkId)
        
        val referrerData = ReferrerData(
            referrerString = referrerString,
            fpId = fpId,
            deeplinkId = deeplinkId,
            deeplinkData = deeplinkData,
            processedAt = System.currentTimeMillis()
        )
        
        this.referrerData = referrerData
        cacheReferrerData(referrerData)
        
        log("Install referrer processing complete")
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
        
        // Check if dimension overrides are set in config
        val screenWidth: Int
        val screenHeight: Int 
        val pixelRatio: Float
        
        // Use overrides if provided (for testing)
        if (config.overrideScreenWidth != null && 
            config.overrideScreenHeight != null) {
            
            screenWidth = config.overrideScreenWidth
            screenHeight = config.overrideScreenHeight
            pixelRatio = config.overridePixelRatio ?: context.resources.displayMetrics.density
            
            Log.d("DLN_Screen", "Using override dimensions: width=$screenWidth, height=$screenHeight, density=$pixelRatio")
        } else {
            // Otherwise use standard detection with reasonableness checks
            val displayMetrics = context.resources.displayMetrics
            
            // For emulators/simulators, sometimes we need to check if these values are reasonable
            val reasonableMinWidth = 320
            val reasonableMaxWidth = 1440
            val reasonableMinHeight = 480
            val reasonableMaxHeight = 2960
            
            if (displayMetrics.widthPixels >= reasonableMinWidth && 
                displayMetrics.widthPixels <= reasonableMaxWidth &&
                displayMetrics.heightPixels >= reasonableMinHeight && 
                displayMetrics.heightPixels <= reasonableMaxHeight) {
                // Values look reasonable, use them
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
                pixelRatio = displayMetrics.density
                
                Log.d("DLN_Screen", "Using displayMetrics directly: width=$screenWidth, height=$screenHeight, density=$pixelRatio")
            } else {
                // Values don't look reasonable, try an alternative approach
                val configuration = context.resources.configuration
                screenWidth = Math.round(configuration.screenWidthDp * displayMetrics.density)
                screenHeight = Math.round(configuration.screenHeightDp * displayMetrics.density)
                pixelRatio = displayMetrics.density
                
                Log.d("DLN_Screen", "Using configuration: original width=${displayMetrics.widthPixels}, " +
                        "height=${displayMetrics.heightPixels} were unreasonable. " +
                        "New width=$screenWidth, height=$screenHeight, density=$pixelRatio")
            }
        }
        
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
        
        // Process install referrer for deferred deep linking
        processInstallReferrer()
        
        // Register any pending callback
        registerPendingCallback()
    }

    private fun registerPendingCallback() {
        Companion.pendingCallback?.let { callback ->
            log("Registering pending deep link callback")
            setDeepLinkCallbackInternal(callback)
            Companion.pendingCallback = null
        }
    }

    fun isValidDomain(domain: String): Boolean {
        // Check exact matches first (for custom domains)
        if (validDomains.contains(domain)) {
            return true
        }
        
        // Check if it's a subdomain of deeplinknow.com
        if (domain.endsWith(".deeplinknow.com") || domain == "deeplinknow.com") {
            return true
        }
        
        // Check if it's a subdomain of deeplink.now
        if (domain.endsWith(".deeplink.now") || domain == "deeplink.now") {
            return true
        }
        
        log("Domain '$domain' is not valid. Valid domains: $validDomains")
        return false
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

    private fun setDeepLinkCallbackInternal(callback: DeepLinkCallback?) {
        deepLinkCallback = callback
        log("Deep link callback ${if (callback != null) "registered" else "removed"}")
    }

    private fun handleDeepLinkInternal(url: String): Boolean {
        log("Handling deep link: $url")
        
        try {
            val uri = Uri.parse(url)
            val host = uri.host ?: ""
            
            // Check if it's a valid domain (deeplinknow.com or custom domains)
            if (!isValidDomain(host)) {
                log("Invalid domain: $host")
                return false
            }
            
            log("Deep link captured from valid domain: $host")
            
            // Extract route (path without leading slash)
            val route = uri.path?.removePrefix("/") ?: ""
            
            // Extract parameters from query string
            val params = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { value ->
                    params[key] = value
                }
            }
            
            log("Parsed route: '$route', params: $params")
            
            // Create deep link data
            val deepLinkData = DeepLinkData(
                route = route,
                params = params
            )
            
            // Trigger callback if registered
            deepLinkCallback?.let { callback ->
                try {
                    callback.onDeepLinkOpen(deepLinkData)
                    log("Deep link callback triggered successfully")
                } catch (e: Exception) {
                    warn("Error in deep link callback", e)
                }
            } ?: run {
                log("No deep link callback registered")
            }
            
            return true
            
        } catch (e: Exception) {
            warn("Error handling deep link", e)
            return false
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
                if (domain != null && isValidDomain(domain)) {
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

    fun getDeferredDeepLink(): DeeplinkMatch? {
        log("Getting deferred deep link data")
        
        // If referrer data hasn't been loaded yet, try to get it from cache
        if (referrerData == null) {
            referrerData = getCachedReferrerData()
        }
        
        return referrerData?.deeplinkData?.also {
            log("Found deferred deep link: $it")
        }
    }

    fun getReferrerData(): ReferrerData? {
        log("Getting referrer data")
        
        // If referrer data hasn't been loaded yet, try to get it from cache
        if (referrerData == null) {
            referrerData = getCachedReferrerData()
        }
        
        return referrerData?.also {
            log("Found referrer data: $it")
        }
    }

    private fun isInitialized(): Boolean {
        return instance?.get() != null
    }

    companion object {
        // Use a WeakReference to prevent memory leaks
        private var instance: WeakReference<DLN>? = null
        private var pendingCallback: DeepLinkCallback? = null

        @JvmStatic
        @JvmOverloads
        fun init(
            context: Context,
            apiKey: String,
            enableLogs: Boolean = false,
            overrideScreenWidth: Int? = null,
            overrideScreenHeight: Int? = null,
            overridePixelRatio: Float? = null
        ) {
            instance = WeakReference(
                DLN(
                    config = DLNConfig(
                        apiKey = apiKey,
                        enableLogs = enableLogs,
                        overrideScreenWidth = overrideScreenWidth,
                        overrideScreenHeight = overrideScreenHeight,
                        overridePixelRatio = overridePixelRatio
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

        @JvmStatic
        fun setDeepLinkCallback(callback: DeepLinkCallback?) {
            try {
                // Try to set the callback on the initialized instance
                getInstance().setDeepLinkCallbackInternal(callback)
            } catch (e: IllegalStateException) {
                // If not initialized yet, store the callback for later
                pendingCallback = callback
            }
        }

        @JvmStatic
        fun handleDeepLink(url: String): Boolean {
            return try {
                getInstance().handleDeepLinkInternal(url)
            } catch (e: IllegalStateException) {
                // If not initialized yet, we can't handle the deep link
                false
            }
        }
    }
}