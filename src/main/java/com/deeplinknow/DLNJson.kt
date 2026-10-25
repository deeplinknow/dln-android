package com.deeplinknow

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Hand-written decoders for server and on-disk JSON. They never depend on reflection (so R8 in
 * host apps cannot break them), tolerate missing optional fields and unknown fields (SDK-51),
 * and never leave `null` in a non-null Kotlin property. A structurally invalid body throws
 * [DLNJson.Invalid], which callers map to `invalidResponse`.
 */
internal object DLNJson {
    class Invalid(message: String) : Exception(message)

    fun parse(body: String?): JsonElement {
        if (body.isNullOrBlank()) throw Invalid("empty body")
        return try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw Invalid("malformed JSON")
        }
    }

    private fun JsonElement?.obj(): JsonObject? = if (this != null && this.isJsonObject) this.asJsonObject else null
    private fun JsonObject.child(key: String): JsonElement? = get(key)?.takeUnless { it is JsonNull }
    private fun JsonObject.str(key: String): String? =
        child(key)?.let { if (it.isJsonPrimitive) it.asJsonPrimitive.let { p -> if (p.isString || p.isNumber || p.isBoolean) p.asString else null } else null }
    private fun JsonObject.bool(key: String): Boolean? =
        child(key)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else null }
    private fun JsonObject.num(key: String): Double? =
        child(key)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asDouble else null }

    /** Converts JSON to plain Kotlin values: Map, List, String, Boolean, Double, or null. */
    fun toPlain(element: JsonElement?): Any? = when {
        element == null || element.isJsonNull -> null
        element.isJsonObject -> LinkedHashMap<String, Any?>().apply {
            element.asJsonObject.entrySet().forEach { (k, v) -> put(k, toPlain(v)) }
        }
        element.isJsonArray -> element.asJsonArray.map { toPlain(it) }
        else -> {
            val p = element.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble
                else -> p.asString
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun metadataOf(element: JsonElement?): Map<String, Any> {
        val plain = toPlain(element.obj() ?: return emptyMap()) as Map<String, Any?>
        return plain.filterValues { it != null } as Map<String, Any>
    }

    /** `query_params` as plain values (String / true / List<String>), or null when absent/not an object. */
    fun queryParamsOf(element: JsonElement?): Map<String, Any>? {
        val o = element.obj() ?: return null
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in o.entrySet()) {
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> if (value.asBoolean) out[key] = true
                value.isJsonPrimitive -> out[key] = value.asString
                value.isJsonArray -> out[key] = value.asJsonArray.mapNotNull { if (it.isJsonNull) null else if (it.isJsonPrimitive) it.asString else it.toString() }
            }
        }
        return out
    }

    fun decodeDeeplink(element: JsonElement?): DeeplinkMatch? {
        val o = element.obj() ?: return null
        val id = o.str("id") ?: throw Invalid("deeplink.id missing")
        return DeeplinkMatch(
            id = id,
            targetUrl = o.str("target_url") ?: "",
            metadata = metadataOf(o.child("metadata")),
            campaignId = o.str("campaign_id"),
            matchedAt = o.str("matched_at") ?: "",
            expiresAt = o.str("expires_at") ?: "",
            queryParams = queryParamsOf(o.child("query_params")),
            fullPath = o.str("full_path"),
        )
    }

    /** `GET /api/v1/sdk/resolve` 200 body: `{"deeplink": {…}}` (API-SDK-71..75, SDK-90). */
    fun decodeResolve(element: JsonElement): DeeplinkMatch {
        val o = element.obj() ?: throw Invalid("resolve response is not an object")
        return decodeDeeplink(o.child("deeplink")) ?: throw Invalid("resolve response has no deeplink")
    }

    private fun decodeDetails(element: JsonElement?): MatchDetails {
        val o = element.obj() ?: JsonObject()
        val ip = o.child("ip_match").obj() ?: JsonObject()
        val device = o.child("device_match").obj() ?: JsonObject()
        val deviceComponents = device.child("components").obj() ?: JsonObject()
        val time = o.child("time_proximity").obj() ?: JsonObject()
        val locale = o.child("locale_match").obj() ?: JsonObject()
        val localeComponents = locale.child("components").obj() ?: JsonObject()
        return MatchDetails(
            ipMatch = MatchComponentDetails(ip.bool("matched") ?: false, ip.num("score") ?: 0.0),
            deviceMatch = DeviceMatchDetails(
                matched = device.bool("matched") ?: false,
                score = device.num("score") ?: 0.0,
                components = DeviceMatchDetails.DeviceComponents(
                    platform = deviceComponents.bool("platform") ?: false,
                    osVersion = deviceComponents.bool("os_version") ?: false,
                    deviceModel = deviceComponents.bool("device_model") ?: false,
                    hardwareFingerprint = deviceComponents.bool("hardware_fingerprint") ?: false,
                ),
            ),
            timeProximity = TimeProximityDetails(
                score = time.num("score") ?: 0.0,
                timeDifferenceMinutes = (time.num("time_difference_minutes") ?: 0.0).toInt(),
            ),
            localeMatch = LocaleMatchDetails(
                matched = locale.bool("matched") ?: false,
                score = locale.num("score") ?: 0.0,
                components = LocaleMatchDetails.LocaleComponents(
                    language = localeComponents.bool("language") ?: false,
                    timezone = localeComponents.bool("timezone") ?: false,
                ),
            ),
        )
    }

    fun decodeMatchResponse(element: JsonElement): MatchResponse {
        val o = element.obj() ?: throw Invalid("match response is not an object")
        val matchesEl = o.child("matches")
        if (matchesEl == null || !matchesEl.isJsonArray) throw Invalid("matches missing")
        val matches = (matchesEl as JsonArray).mapNotNull { m ->
            val mo = m.obj() ?: return@mapNotNull null
            Match(
                deeplink = decodeDeeplink(mo.child("deeplink")),
                confidenceScore = mo.num("confidence_score") ?: throw Invalid("confidence_score missing"),
                matchDetails = decodeDetails(mo.child("match_details")),
                matchQuality = mo.str("match_quality"),
            )
        }
        return MatchResponse(matches = matches, ttlSeconds = (o.num("ttl_seconds") ?: 3600.0).toInt())
    }

    fun decodeInitResponse(element: JsonElement): InitResponse {
        val o = element.obj() ?: throw Invalid("init response is not an object")
        val app = o.child("app").obj() ?: throw Invalid("app missing")
        val account = o.child("account").obj() ?: JsonObject()
        val limits = account.child("rate_limits").obj() ?: JsonObject()
        val domains = (app.child("custom_domains")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { it.obj() }
            .map { InitResponse.App.CustomDomain(domain = it.str("domain"), verified = it.bool("verified")) }
        val certificates = app.child("android_sha256_certificates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
        return InitResponse(
            app = InitResponse.App(
                id = app.str("id") ?: throw Invalid("app.id missing"),
                name = app.str("name") ?: "",
                timezone = app.str("timezone") ?: "UTC",
                androidPackageName = app.str("android_package_name"),
                androidSha256Cert = app.str("android_sha256_cert"),
                iosBundleId = app.str("ios_bundle_id"),
                iosAppStoreId = app.str("ios_app_store_id"),
                iosAppPrefix = app.str("ios_app_prefix"),
                customDomains = domains,
                alias = app.str("alias"),
                androidSha256Certificates = certificates,
            ),
            account = InitResponse.Account(
                status = account.str("status") ?: "active",
                creditsRemaining = (account.num("credits_remaining") ?: 0.0).toInt(),
                rateLimits = InitResponse.Account.RateLimits(
                    matchesPerSecond = (limits.num("matches_per_second") ?: 0.0).toInt(),
                    matchesPerDay = (limits.num("matches_per_day") ?: 0.0).toInt(),
                ),
            ),
        )
    }

    /** Referrer lookup body: `{success, deeplink?, message?}` (or `{error}` for auth errors). */
    fun decodeReferrerLookup(element: JsonElement): ReferrerLookupResponse {
        val o = element.obj() ?: throw Invalid("lookup response is not an object")
        return ReferrerLookupResponse(
            success = o.bool("success") ?: false,
            deeplink = decodeDeeplink(o.child("deeplink")),
            message = o.str("message") ?: o.str("error"),
        )
    }

    /** Server `error` (or referrer-lookup `message`) string from an error body, if any. */
    fun errorMessage(body: String?): String? {
        if (body == null) return null
        return try {
            val o = JsonParser.parseString(body).obj()
            o?.str("error") ?: o?.str("message")
        } catch (e: Exception) {
            null
        }
    }

    // ---- On-disk ReferrerData ----

    fun encodeReferrerData(data: ReferrerData): JsonObject = JsonObject().apply {
        addProperty("referrerString", data.referrerString)
        add("fpId", data.fpId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("deeplinkId", data.deeplinkId?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("deeplinkData", data.deeplinkData?.let { encodeDeeplink(it) } ?: JsonNull.INSTANCE)
        addProperty("processedAt", data.processedAt)
    }

    fun encodeDeeplink(d: DeeplinkMatch): JsonObject = JsonObject().apply {
        addProperty("id", d.id)
        addProperty("target_url", d.targetUrl)
        add("metadata", com.google.gson.Gson().toJsonTree(d.metadata))
        d.campaignId?.let { addProperty("campaign_id", it) }
        addProperty("matched_at", d.matchedAt)
        addProperty("expires_at", d.expiresAt)
        d.queryParams?.let { add("query_params", com.google.gson.Gson().toJsonTree(it)) }
        d.fullPath?.let { addProperty("full_path", it) }
    }

    fun decodeReferrerData(element: JsonElement?): ReferrerData? {
        val o = element.obj() ?: return null
        return ReferrerData(
            referrerString = o.str("referrerString") ?: "",
            fpId = o.str("fpId"),
            deeplinkId = o.str("deeplinkId"),
            deeplinkData = try {
                decodeDeeplink(o.child("deeplinkData"))
            } catch (e: Invalid) {
                null
            },
            processedAt = (o.num("processedAt") ?: 0.0).toLong(),
        )
    }
}
