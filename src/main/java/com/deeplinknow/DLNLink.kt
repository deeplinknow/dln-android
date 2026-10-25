package com.deeplinknow

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// One link object for every open (1.4.0; docs/sdks/overview.md SDK-86..91, android.md AND-35..40)
// ---------------------------------------------------------------------------------------------

/** How a [DLNLink] reached the app (SDK-86). [value] is the shared wire name. */
enum class DLNLinkType(val value: String) {
    /** Delivered by the first-launch chain after an install. */
    DEFERRED("deferred"),

    /** The OS opened the installed app with this URL. */
    DIRECT("direct"),
}

/**
 * Where a [DLNLink] came from: the three deferred sources of [DeferredDeepLinkSource], or how a
 * direct open arrived. Android direct opens are [APP_LINK] (https on the app's link domains) or
 * [SCHEME] (the app's custom URL scheme); [UNIVERSAL_LINK] is iOS-only and listed for parity.
 * [value] is the shared wire name.
 */
enum class DLNLinkSource(val value: String) {
    REFERRER("referrer"),
    CLIPBOARD("clipboard"),
    FINGERPRINT("fingerprint"),
    UNIVERSAL_LINK("universalLink"),
    APP_LINK("appLink"),
    SCHEME("scheme"),
    ;

    companion object {
        /** The [DLNLinkSource] for a deferred result's source. */
        @JvmStatic
        fun from(source: DeferredDeepLinkSource): DLNLinkSource = when (source) {
            DeferredDeepLinkSource.REFERRER -> REFERRER
            DeferredDeepLinkSource.CLIPBOARD -> CLIPBOARD
            DeferredDeepLinkSource.FINGERPRINT -> FINGERPRINT
        }
    }
}

/**
 * The link the [DLN.onLink] handler receives for every open (SDK-86): every [DeferredDeepLink]
 * field with the same meaning, plus [type]. For a direct open, [confidenceScore] is 100,
 * [matchQuality] HIGH, and the server fields are null unless the open was resolved (SDK-90).
 */
data class DLNLink(
    /** `fullPath` up to the first `?`, not decoded, starts with `/`. */
    val path: String,
    /** Query parameters with URLSearchParams semantics (string | true | list). */
    val queryParams: Map<String, QueryValue>,
    /** `/` + path + `?query` as tapped. */
    val fullPath: String,
    /** The link's URL: the absolute https URL for deferred links, the opened URL for direct ones. */
    val url: String,
    val source: DLNLinkSource,
    /** 0–100. 100 for referrer, clipboard and direct opens. */
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
    val type: DLNLinkType,
) {
    companion object {
        /** A deferred result as a [DLNLink] (`type = DEFERRED`), field for field. */
        @JvmStatic
        fun fromDeferred(link: DeferredDeepLink): DLNLink = DLNLink(
            path = link.path,
            queryParams = link.queryParams,
            fullPath = link.fullPath,
            url = link.url,
            source = DLNLinkSource.from(link.source),
            confidenceScore = link.confidenceScore,
            matchQuality = link.matchQuality,
            deeplinkId = link.deeplinkId,
            targetUrl = link.targetUrl,
            campaignId = link.campaignId,
            metadata = link.metadata,
            matchedAt = link.matchedAt,
            expiresAt = link.expiresAt,
            matchDetails = link.matchDetails,
            type = DLNLinkType.DEFERRED,
        )

        internal fun direct(parsed: ParsedDeepLink, source: DLNLinkSource): DLNLink = DLNLink(
            path = parsed.path,
            queryParams = parsed.queryParams,
            fullPath = parsed.fullPath,
            url = parsed.url,
            source = source,
            confidenceScore = 100.0,
            matchQuality = MatchQuality.HIGH,
            type = DLNLinkType.DIRECT,
        )
    }
}

/**
 * Options for [DLN.onLink].
 *
 * @property minConfidence threshold for fingerprint candidates of the deferred check (off by default, SDK-14).
 * @property force ignore the first-launch flag for the deferred check (SDK-23).
 * @property hold keep links in [DLN.pendingLink] instead of calling the handler until
 *   [DLN.releasePendingLink], which delivers the held link and ends the hold (SDK-89).
 * @property resolve look up each direct open on the app's domains with `GET /api/v1/sdk/resolve`
 *   before delivering it (SDK-90). Off by default: it adds a network call to every open.
 * @property schemes the app's custom URL schemes (`"myapp"`), without `://`. When empty, a
 *   scheme counts as the app's when the app's manifest has a VIEW intent filter for it (AND-36).
 */
data class DLNLinkOptions @JvmOverloads constructor(
    val minConfidence: Double? = null,
    val force: Boolean = false,
    val hold: Boolean = false,
    val resolve: Boolean = false,
    val schemes: Set<String> = emptySet(),
)

/** Java-friendly handler for [DLN.onLink]. Called on the main thread. */
interface DLNLinkHandler {
    fun onLink(link: DLNLink)
}

/** Java-friendly callback for [DLN.resolveLink]: the link, or null when the URL is not one of the app's. */
interface DLNLinkResultCallback {
    fun onResult(link: DLNLink?)
}

/** Returned by [DLN.onLink]. [remove] unregisters that handler (a later registration is untouched). */
class DLNLinkSubscription internal constructor(private val token: Any) {
    fun remove() = DLNLinkRouter.unregister(token)
}

/**
 * Process-wide link delivery state. It lives outside the [DLN] instance so the handler, a held
 * link and the "deferred check ran" state carry over when [DLN.init] replaces the instance
 * (AND-33 applied to [DLN.onLink], AND-39).
 */
internal object DLNLinkRouter {
    private const val TAG = "DLN"

    /** Marks an intent the SDK already handled (SDK-88, AND-37). */
    const val EXTRA_HANDLED = "com.deeplinknow.LINK_HANDLED"

    /** The same direct URL within this window is one open replayed by the OS (SDK-88). */
    internal var duplicateWindowMs = 3_000L
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private enum class DeferredState { IDLE, RUNNING, DONE }

    private val lock = Any()
    private var handler: ((DLNLink) -> Unit)? = null
    private var handlerToken: Any? = null
    private var options = DLNLinkOptions()
    /** SDK-89: while held, the latest link of each kind (deferred first on release). */
    private var pendingDeferred: DLNLink? = null
    private var pendingDirect: DLNLink? = null
    private var undeliveredDeferred: DLNLink? = null
    private var undeliveredDirect: DLNLink? = null
    private var deferredState = DeferredState.IDLE
    private var lastDirectUrl: String? = null
    private var lastDirectAt = 0L

    private fun warn(message: String) = Log.w(TAG, message)

    // ---- handler ----

    fun register(newOptions: DLNLinkOptions, newHandler: (DLNLink) -> Unit): DLNLinkSubscription {
        val token = Any()
        try {
            val toDeliver = ArrayList<DLNLink>()
            synchronized(lock) {
                handler = newHandler
                handlerToken = token
                options = newOptions
                // Links queued with no handler are the newest of their kind; keep one per kind.
                val deferred = undeliveredDeferred ?: pendingDeferred
                val direct = undeliveredDirect ?: pendingDirect
                undeliveredDeferred = null
                undeliveredDirect = null
                pendingDeferred = null
                pendingDirect = null
                if (newOptions.hold) {
                    pendingDeferred = deferred
                    pendingDirect = direct
                } else {
                    toDeliver += listOfNotNull(deferred, direct) // deferred first (SDK-88)
                }
            }
            toDeliver.forEach { post(newHandler, it) }
            maybeStartDeferred()
        } catch (e: Exception) {
            warn("DLN.onLink failed: ${e.javaClass.simpleName}")
        }
        return DLNLinkSubscription(token)
    }

    fun unregister(token: Any) {
        synchronized(lock) {
            if (handlerToken === token) {
                handler = null
                handlerToken = null
                options = DLNLinkOptions()
            }
        }
    }

    private fun post(target: (DLNLink) -> Unit, link: DLNLink) {
        DLN.postMain { target(link) }
    }

    /** Hands [link] to the handler, the hold slot, or the replay slots (SDK-86, SDK-89). */
    fun deliver(link: DLNLink) {
        var target: ((DLNLink) -> Unit)? = null
        synchronized(lock) {
            val current = handler
            when {
                current == null -> if (link.type == DLNLinkType.DEFERRED) undeliveredDeferred = link else undeliveredDirect = link
                options.hold -> hold(link)
                else -> target = current
            }
        }
        target?.let { post(it, link) }
    }

    // ---- hold (SDK-89) ----

    /** Call with [lock] held. Only the latest link of each kind is kept. */
    private fun hold(link: DLNLink) {
        if (link.type == DLNLinkType.DEFERRED) pendingDeferred = link else pendingDirect = link
    }

    val pendingLinks: List<DLNLink>
        get() = synchronized(lock) { listOfNotNull(pendingDeferred, pendingDirect) }

    val pendingLink: DLNLink?
        get() = pendingLinks.firstOrNull()

    fun releasePending(): Boolean = try {
        var target: ((DLNLink) -> Unit)? = null
        val links = synchronized(lock) {
            // SDK-89: releasing ends the hold, so later links go straight to the handler.
            if (options.hold) options = options.copy(hold = false)
            val held = listOfNotNull(pendingDeferred, pendingDirect)
            pendingDeferred = null
            pendingDirect = null
            target = handler
            if (target == null) {
                held.forEach { if (it.type == DLNLinkType.DEFERRED) undeliveredDeferred = it else undeliveredDirect = it }
            }
            held
        }
        val t = target
        if (links.isNotEmpty() && t != null) {
            links.forEach { post(t, it) }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    fun clearPending() {
        synchronized(lock) {
            pendingDeferred = null
            pendingDirect = null
        }
    }

    // ---- the deferred check (SDK-86, SDK-91) ----

    /** Starts the one deferred check of this process when a handler and an instance exist. */
    fun maybeStartDeferred() {
        try {
            val dln: DLN
            val opts: DLNLinkOptions
            synchronized(lock) {
                if (handler == null || deferredState != DeferredState.IDLE) return
                dln = DLN.instance ?: return
                deferredState = DeferredState.RUNNING
                opts = options
            }
            DLN.sdkScope.launch { runDeferred(dln, opts) }
        } catch (e: Exception) {
            warn("DLN.onLink: could not start the deferred check (${e.javaClass.simpleName})")
        }
    }

    private suspend fun runDeferred(dln: DLN, opts: DLNLinkOptions) {
        val result: Result<DeferredDeepLink?> = try {
            dln.checkDeferred(opts.minConfidence, opts.force)
        } catch (e: CancellationException) {
            Result.failure(DLNException(DLNErrorCode.NOT_INITIALIZED, cause = e))
        } catch (e: Exception) {
            Result.failure(DLNException(DLNErrorCode.INVALID_RESPONSE, cause = e))
        }
        DLN.warnCheckFailure(result)
        DLN.recordOutcome(DLN.Companion.ErrorKind.CHECK, result)
        // SDK-91: the app's own checkDeferredDeepLink may have taken this process's link first.
        val link = if (result.isSuccess) result.getOrNull() ?: dln.processDeferredLink else null
        synchronized(lock) {
            deferredState = if (result.isSuccess) DeferredState.DONE else DeferredState.IDLE
        }
        link?.let {
            dln.logDebug("onLink: deferred link ${it.fullPath} (${it.source.value})")
            deliver(DLNLink.fromDeferred(it))
        }
        // A replacement init that arrived while this check ran gets its own try (SDK-24).
        if (result.isFailure && DLN.instance !== dln) maybeStartDeferred()
    }

    fun resetDeferred() {
        synchronized(lock) {
            if (deferredState == DeferredState.DONE) deferredState = DeferredState.IDLE
        }
    }

    // ---- direct opens (SDK-87, SDK-88) ----

    fun handleIntent(intent: Intent?): Boolean {
        return try {
            if (intent == null) return false
            val url = intent.dataString?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            val alreadyHandled = try {
                intent.getBooleanExtra(EXTRA_HANDLED, false)
            } catch (e: Exception) {
                false
            }
            // AND-37: reopening from Recents replays the original intent; it is not a new open.
            val accepted = handleUrl(url, deliver = !fromHistory && !alreadyHandled)
            if (accepted && !alreadyHandled) {
                try {
                    intent.putExtra(EXTRA_HANDLED, true)
                } catch (e: Exception) {
                    // the dedupe window still applies
                }
            }
            accepted
        } catch (e: Exception) {
            warn("DLN.handleIntent failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun isAppScheme(uri: Uri, dln: DLN?): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (!DLNLinks.isCustomSchemeCandidate(scheme)) return false
        val configured = synchronized(lock) { options.schemes }
        if (configured.isNotEmpty()) {
            return configured.any { it.trim().removeSuffix("://").removeSuffix(":").lowercase(Locale.ROOT) == scheme }
        }
        return dln?.appDeclaresScheme(uri) == true
    }

    /** True when this open is a repeat of the last one within the window (SDK-88). */
    private fun isDuplicate(url: String): Boolean = synchronized(lock) {
        val now = clock()
        val duplicate = lastDirectUrl == url && now - lastDirectAt in 0 until duplicateWindowMs
        lastDirectUrl = url
        lastDirectAt = now
        duplicate
    }

    private fun handleUrl(url: String, deliver: Boolean): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val dln = DLN.instance
        if (scheme == "https" || scheme == "http") {
            if (dln != null && !dln.initComplete) {
                // AND-35: the link domains arrive with init; decide once it finishes.
                if (!looksLikeHttpLink(url)) return false
                if (deliver && !isDuplicate(url)) {
                    DLN.sdkScope.launch {
                        dln.awaitInit()
                        val parsed = dln.parseLink(url)
                        if (parsed == null) {
                            dln.logDebug("handleIntent: not a link on this app's domains")
                        } else {
                            finishDirect(dln, DLNLink.direct(parsed, DLNLinkSource.APP_LINK))
                        }
                    }
                }
                return true
            }
            val parsed = (if (dln != null) dln.parseLink(url) else DLNLinks.parse(url, null)) ?: return false
            if (deliver && !isDuplicate(url)) {
                val link = DLNLink.direct(parsed, DLNLinkSource.APP_LINK)
                if (dln != null && synchronized(lock) { options.resolve }) {
                    DLN.sdkScope.launch { finishDirect(dln, link) }
                } else {
                    deliver(link)
                }
            }
            return true
        }
        if (!isAppScheme(uri, dln)) return false
        val parsed = DLNLinks.parseScheme(url) ?: return false
        if (deliver && !isDuplicate(url)) deliver(DLNLink.direct(parsed, DLNLinkSource.SCHEME))
        return true
    }

    /** An absolute http(s) URL with a host and no credentials (the pre-init acceptance check). */
    private fun looksLikeHttpLink(url: String): Boolean = try {
        val uri = Uri.parse(url)
        uri.isHierarchical && !uri.host.isNullOrEmpty() && uri.encodedUserInfo == null
    } catch (e: Exception) {
        false
    }

    private suspend fun finishDirect(dln: DLN, link: DLNLink) {
        val resolveOn = synchronized(lock) { options.resolve }
        deliver(if (resolveOn) resolved(dln, link) else link)
    }

    /** SDK-90: fills the server fields from `/resolve`, or returns [link] unchanged. */
    private suspend fun resolved(dln: DLN, link: DLNLink): DLNLink {
        if (link.source != DLNLinkSource.APP_LINK) return link
        val match = dln.resolvePath(link.fullPath) ?: return link
        dln.logDebug("resolve: ${link.fullPath} → deeplink ${match.id}")
        return link.copy(
            deeplinkId = match.id,
            targetUrl = match.targetUrl,
            campaignId = match.campaignId,
            metadata = match.metadata,
        )
    }

    suspend fun resolveLink(url: String): DLNLink? {
        return try {
            val trimmed = url.trim()
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val dln = DLN.instance
            if (scheme == "https" || scheme == "http") {
                dln?.awaitInit()
                val parsed = (if (dln != null) dln.parseLink(trimmed) else DLNLinks.parse(trimmed, null)) ?: return null
                val link = DLNLink.direct(parsed, DLNLinkSource.APP_LINK)
                if (dln != null) resolved(dln, link) else link
            } else if (isAppScheme(uri, dln)) {
                DLNLinks.parseScheme(trimmed)?.let { DLNLink.direct(it, DLNLinkSource.SCHEME) }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("DLN.resolveLink failed: ${e.javaClass.simpleName}")
            null
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            handler = null
            handlerToken = null
            options = DLNLinkOptions()
            pendingDeferred = null
            pendingDirect = null
            undeliveredDeferred = null
            undeliveredDirect = null
            deferredState = DeferredState.IDLE
            lastDirectUrl = null
            lastDirectAt = 0L
        }
        duplicateWindowMs = 3_000L
        clock = { SystemClock.elapsedRealtime() }
    }
}
