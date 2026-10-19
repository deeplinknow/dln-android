package com.deeplinknow

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * A query parameter value with URLSearchParams semantics (SDK-35 / FLOW-53):
 * one occurrence → [Single], one empty occurrence (`?k` / `?k=`) → [Flag],
 * repeated key → [Multiple] (values in order, empty values kept as "").
 */
sealed interface QueryValue {
    data class Single(val value: String) : QueryValue

    object Flag : QueryValue {
        override fun toString(): String = "Flag"
    }

    data class Multiple(val values: List<String>) : QueryValue

    /** The cross-platform plain shape: `String`, `true` or `List<String>` (TS `string | true | string[]`). */
    fun toPlain(): Any = when (this) {
        is Single -> value
        Flag -> true
        is Multiple -> values
    }

    /** First value as a string (`""` for [Flag]); the legacy `Map<String, String>` view. */
    fun firstValue(): String = when (this) {
        is Single -> value
        Flag -> ""
        is Multiple -> values.firstOrNull() ?: ""
    }

    companion object {
        /** Converts a decoded JSON value (`String`, `Boolean`, `List`) to a [QueryValue], or null. */
        @JvmStatic
        fun fromPlain(value: Any?): QueryValue? = when (value) {
            is String -> if (value.isEmpty()) Flag else Single(value)
            true -> Flag
            is List<*> -> Multiple(value.map { it?.toString() ?: "" })
            is Number -> Single(value.toString())
            else -> null
        }
    }
}

/** A link on one of the app's domains, parsed with SDK-30..36. */
data class ParsedDeepLink(
    /** `fullPath` up to the first `?`, not decoded, always starts with `/` (SDK-34). */
    val path: String,
    /** Query parameters with URLSearchParams semantics, in first-appearance order (SDK-35). */
    val queryParams: Map<String, QueryValue>,
    /** `/` + path + `?query` exactly as in the URL, fragment excluded (SDK-36). */
    val fullPath: String,
    /** The URL that was parsed (trimmed). */
    val url: String,
)

/** Pure link helpers shared by parseDeepLink, checkClipboard, isValidDomain and referrer parsing. */
internal object DLNLinks {
    const val DLN_SUFFIX = ".deeplinknow.com"
    const val DLN_APEX = "deeplinknow.com"
    private val RESERVED = setOf("www", "app", "api", "docs", "m", "mail")
    private val ALIAS = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Lowercases, drops a port and a trailing `.` (SDK-31). */
    fun normalizeHost(raw: String?): String {
        var host = (raw ?: "").trim().lowercase(Locale.ROOT)
        val colon = host.lastIndexOf(':')
        if (colon >= 0 && !host.startsWith("[")) host = host.substring(0, colon)
        return host.trimEnd('.')
    }

    /**
     * SDK-31 / SDK-37. [linkDomains] is the exact domain set after a successful init, or null
     * before init (provisional rule: any non-reserved `<alias>.deeplinknow.com`).
     */
    fun isAllowedHost(rawHost: String?, linkDomains: Set<String>?): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty() || host == DLN_APEX) return false
        if (host == "deeplink.now" || host.endsWith(".deeplink.now")) return false
        if (host.endsWith(DLN_SUFFIX)) {
            val label = host.removeSuffix(DLN_SUFFIX)
            if (label in RESERVED || !ALIAS.matches(label)) return false
        }
        return if (linkDomains != null) {
            host in linkDomains
        } else {
            host.endsWith(DLN_SUFFIX)
        }
    }

    /** SDK-30..36. Returns null for non-http(s) URLs, other hosts or malformed input. Never throws. */
    fun parse(rawUrl: String?, linkDomains: Set<String>?): ParsedDeepLink? {
        return try {
            val url = rawUrl?.trim() ?: return null
            if (url.isEmpty()) return null
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (!uri.isHierarchical) return null
            // Reject userinfo tricks outright (https://a.deeplinknow.com@evil.com/): Uri.host is
            // already the real host, but a link with credentials is never a DLN link.
            if (uri.encodedUserInfo != null) return null
            if (!isAllowedHost(uri.host, linkDomains)) return null
            val encodedPath = uri.encodedPath.orEmpty()
            val path = if (encodedPath.isEmpty()) "/" else encodedPath
            val query = uri.encodedQuery
            val fullPath = if (query.isNullOrEmpty()) path else "$path?$query"
            ParsedDeepLink(path = path, queryParams = parseQuery(query), fullPath = fullPath, url = url)
        } catch (e: Exception) {
            null
        }
    }

    /** FLOW-52: `fullPath` up to the first `?` (and before any `#`), undecoded; empty becomes `/`. */
    fun pathFromFullPath(fullPath: String): String {
        val noFragment = fullPath.substringBefore('#')
        val path = noFragment.substringBefore('?')
        return if (path.isEmpty()) "/" else path
    }

    /** The query part of a full path (without `?` and `#fragment`). */
    fun queryFromFullPath(fullPath: String): String? {
        val noFragment = fullPath.substringBefore('#')
        val q = noFragment.indexOf('?')
        return if (q == -1) null else noFragment.substring(q + 1)
    }

    /** WHATWG `application/x-www-form-urlencoded` parsing grouped with FLOW-53 rules. */
    fun parseQuery(rawQuery: String?): Map<String, QueryValue> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val query = if (rawQuery.startsWith("?")) rawQuery.substring(1) else rawQuery
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (sequence in query.split('&')) {
            if (sequence.isEmpty()) continue
            val eq = sequence.indexOf('=')
            val name = if (eq == -1) sequence else sequence.substring(0, eq)
            val value = if (eq == -1) "" else sequence.substring(eq + 1)
            grouped.getOrPut(formDecode(name)) { mutableListOf() }.add(formDecode(value))
        }
        val out = LinkedHashMap<String, QueryValue>()
        for ((key, values) in grouped) {
            out[key] = when {
                values.size > 1 -> QueryValue.Multiple(values.toList())
                values[0].isEmpty() -> QueryValue.Flag
                else -> QueryValue.Single(values[0])
            }
        }
        return out
    }

    /** `+` → space, then WHATWG percent-decode to bytes, then UTF-8 decode with U+FFFD for errors. */
    fun formDecode(input: String): String = percentDecode(input.replace('+', ' '))

    fun percentDecode(input: String): String {
        if (input.indexOf('%') == -1) return input
        val bytes = ByteArrayOutputStream(input.length)
        val raw = input.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < raw.size) {
            val b = raw[i]
            if (b == '%'.code.toByte() && i + 2 < raw.size && isHex(raw[i + 1]) && isHex(raw[i + 2])) {
                bytes.write((hexValue(raw[i + 1]) shl 4) or hexValue(raw[i + 2]))
                i += 3
            } else {
                bytes.write(b.toInt())
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("�")
        return decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    private fun isHex(b: Byte): Boolean {
        val c = b.toInt().toChar()
        return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }

    private fun hexValue(b: Byte): Int = Character.digit(b.toInt().toChar(), 16)

    /**
     * AND-15: URL-decode the referrer when it arrives encoded (`fp_id%3D…`), parse it as
     * form data, take the exact keys `fp_id` / `deeplink_id`, keep only UUID values.
     * Returns (fpId, deeplinkId); `fp_id` wins at lookup time when both are present.
     */
    fun parseReferrer(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        var text = raw.trim()
        if (!text.contains('=')) {
            text = try {
                percentDecode(text)
            } catch (e: Exception) {
                return null to null
            }
        }
        var fpId: String? = null
        var deeplinkId: String? = null
        for (sequence in text.split('&')) {
            val eq = sequence.indexOf('=')
            if (eq <= 0) continue
            val key = formDecode(sequence.substring(0, eq)).trim()
            val value = formDecode(sequence.substring(eq + 1)).trim()
            if (!UUID.matches(value)) continue
            when (key) {
                "fp_id" -> if (fpId == null) fpId = value
                "deeplink_id" -> if (deeplinkId == null) deeplinkId = value
            }
        }
        return fpId to deeplinkId
    }
}
