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
import java.util.Locale
import java.util.TimeZone
import java.lang.ref.WeakReference

data class DLNConfig(
    val apiKey: String,
    val enableLogs: Boolean = false
)

data class Fingerprint(
    @SerializedName("user_agent") val userAgent: String,
    val platform: String = "android",
    @SerializedName("os_version") val osVersion: String,
    @SerializedName("device_model") val deviceModel: String,
    val language: String,
    val timezone: String,
    @SerializedName("installed_at") val installedAt: String,
    @SerializedName("last_opened_at") val lastOpenedAt: String,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("advertising_id") val advertisingId: String?,
    @SerializedName("vendor_id") val vendorId: String?,
    @SerializedName("hardware_fingerprint") val hardwareFingerprint: String?
)

data class DeeplinkMatch(
    val id: String,
    @SerializedName("target_url") val targetUrl: String,
    val metadata: Map<String, Any>,
    @SerializedName("campaign_id") val campaignId: String?,
    @SerializedName("matched_at") val matchedAt: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class MatchResponse(
    val match: Match
) {
    data class Match(
        val deeplink: DeeplinkMatch?,
        @SerializedName("confidence_score") val confidenceScore: Double,
        @SerializedName("ttl_seconds") val ttlSeconds: Int
    )
}

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
    private val installTime = Instant.now().toString()
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

    private suspend fun getFingerprint(): Fingerprint {
        return Fingerprint(
            userAgent = "DLN-Android/${Build.VERSION.RELEASE}",
            osVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
            language = Locale.getDefault().language,
            timezone = TimeZone.getDefault().id,
            installedAt = installTime,
            lastOpenedAt = Instant.now().toString(),
            deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
            advertisingId = null, // Implement if needed
            vendorId = null,
            hardwareFingerprint = null
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