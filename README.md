# DeepLinkNow Android SDK

Deferred deep linking for Android. When a user taps a DeepLinkNow link before installing your app, the SDK hands your app the path and query they tapped on first launch. It reads the **Play Install Referrer** (exact) and falls back to **fingerprint matching** (scored). It also validates and parses App Links that open an installed app.

DeepLinkNow is not an analytics or attribution SDK. It never reads the advertising ID and needs no tracking consent. You forward the result to whatever analytics you use.

## Installation

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.4.1")
}
```

```gradle
dependencies {
    implementation 'com.deeplinknow:dln-android:1.4.1'
}
```

- minSdk 24. The library declares `android.permission.INTERNET` itself.
- It ships consumer R8/ProGuard rules for its model classes.

## Quick start

```kotlin
import com.deeplinknow.DLN

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Returns immediately. Init and the Install Referrer read run in the background.
        DLN.init(this, "your-public-api-key", enableLogs = BuildConfig.DEBUG) { result ->
            // Optional, called once on the main thread
            result.onFailure { Log.w("App", "DLN init failed: ${it.message}") }
        }
    }
}

// First activity
lifecycleScope.launch {
    DLN.checkDeferredDeepLink()
        .onSuccess { link -> link?.let { route(it.path, it.queryParams) } }
        .onFailure { error -> /* DLNException: network, invalidApiKey, … The next launch tries again */ }
}
```

`checkDeferredDeepLink()` works like this:

1. It waits for init, and for the Install Referrer read that init started.
2. **Install Referrer**: if the Play Store referrer carries a DLN `fp_id` or `deeplink_id`, it looks that up. The result has `source = REFERRER`, `confidenceScore = 100` and `matchQuality = HIGH`.
3. Otherwise, **fingerprint**: it takes the first server candidate that has a deep link. The result has `source = FINGERPRINT` and the server's score and HIGH/MEDIUM/LOW label.
4. It delivers a link **once per install**. Later calls return `null` without any network access. Errors (offline, 5xx, …) don't count, so the next call retries.

Options:

- `minConfidence` filters fingerprint candidates only. It is off by default.
- `force = true` ignores the first-launch flag.

`DLN.resetDeferredDeepLink()` clears the first-launch state for testing.

The result (`DeferredDeepLink`):

| Field | Meaning |
|---|---|
| `path` | e.g. `/products/123`, not decoded |
| `queryParams` | `Map<String, QueryValue>`: `Single(value)`, `Flag` (`?k` or `?k=`), `Multiple(values)` (repeated key) |
| `fullPath` | Path and query as tapped, e.g. `/products/123?ref=newsletter` |
| `url` | Absolute https URL on your link domain |
| `source` | `REFERRER` or `FINGERPRINT` |
| `confidenceScore`, `matchQuality` | 100/HIGH for referrer; the server values for fingerprint |
| `deeplinkId`, `targetUrl`, `campaignId`, `metadata`, `matchedAt`, `expiresAt`, `matchDetails` | Server details |

From Java, use the callback overload `DLN.checkDeferredDeepLink(null, false, callback)` with a `DeferredDeepLinkCallback`.

## One handler for every open (1.4.0)

From 1.4.0 your app can route every link from one place. The handler gets the same `DLNLink` whether the link came through an install (deferred) or opened the installed app (an App Link or your custom scheme):

```kotlin
// Application.onCreate, after DLN.init
DLN.onLink { link ->
    // link.type: DEFERRED or DIRECT
    // link.source: REFERRER, FINGERPRINT, APP_LINK or SCHEME
    route(link.path, link.queryParams)
}

// The activity with your App Link intent filter
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A re-created activity (back from Recents after the process died) gets its old intent again.
    if (savedInstanceState == null) DLN.handleIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    DLN.handleIntent(intent)
}
```

- Registering the handler runs the deferred check once per process, like `checkDeferredDeepLink()`. Links that arrive before a handler exists are passed to it when it registers.
- `handleIntent` returns `true` for a link on your app's domains or your custom scheme, `false` for anything else. The same intent is only delivered once, even when `onCreate` and `onNewIntent` both see it, the user reopens the app from Recents, or Android re-creates the activity after killing the process. From 1.4.1 the SDK spots a re-created activity itself (when `DLN.init` runs in `Application.onCreate`), so the `savedInstanceState == null` check is a second guard, not a requirement.
- The handler runs on the main thread. `DLNLink` has every `DeferredDeepLink` field plus `type`. For a direct open, `confidenceScore` is 100 and `matchQuality` is `HIGH`.
- **Custom scheme**: `myapp://products/123?x=1` arrives as path `/products/123`. A scheme counts as yours when your manifest has a VIEW intent filter for it, or pass `DLNLinkOptions(schemes = setOf("myapp"))`.
- **Wait for sign-in**: `DLN.onLink(DLNLinkOptions(hold = true)) { … }` keeps the latest deferred and the latest direct link in `DLN.pendingLinks` (deferred first; `DLN.pendingLink` is the first). After sign-in, call `DLN.releasePendingLink()`: it delivers the held links (deferred first) and ends the hold, so later links go straight to your handler. `DLN.clearPendingLink()` drops them and keeps holding. Registering again with `hold = false` also delivers them and stops holding.
- **Imported short links**: `DLNLinkOptions(resolve = true)` looks up each App Link with `GET /api/v1/sdk/resolve` and fills `deeplinkId`, `targetUrl`, `campaignId` and `metadata` before delivering it. A 404, an error or a timeout delivers the link unresolved. `DLN.resolveLink(url)` (suspend) does the same lookup on demand.
- `onLink` returns a `DLNLinkSubscription`; call `remove()` if you register from an activity.

From Java: `DLN.onLink(link -> route(link))`, `DLN.onLink(new DLNLinkOptions(null, false, true), handler)` and `DLN.resolveLink(url, link -> { … })`.

`checkDeferredDeepLink`, `parseDeepLink`, `setDeepLinkCallback` and `handleDeepLink` keep working as before. `handleDeepLink(url)` still calls the `DeepLinkCallback` only; `handleIntent` feeds the `onLink` handler.

## App Links (installed app)

Add an `autoVerify` intent filter for `https://<alias>.deeplinknow.com` (and your verified custom domain), then:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent.data?.toString()?.let { url -> DLN.parseDeepLink(url)?.let { route(it.path, it.queryParams) } }
}
```

`DLN.parseDeepLink(url)` returns `ParsedDeepLink(path, queryParams, fullPath, url)`. It returns `null` when the URL is not on your app's link domains.

After init, only `<alias>.deeplinknow.com` and your verified custom domains are accepted, case-insensitively. Other apps' subdomains, `deeplinknow.com` itself and `deeplink.now` are always rejected.

You can also register a `DeepLinkCallback` with `DLN.setDeepLinkCallback` and call `DLN.handleDeepLink(url)`.

See [DOCS.md](DOCS.md) for the full API and [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for App Link verification.

## Errors

The 1.1 APIs return `Result`. Failures are a `DLNException` with one of these `code`s:

- `NOT_INITIALIZED`
- `INVALID_API_KEY` (401)
- `ACCOUNT_INACTIVE` (403)
- `NO_CREDITS` (403)
- `RATE_LIMITED` (429)
- `BAD_REQUEST` (400)
- `SERVER` (5xx)
- `NETWORK`
- `INVALID_RESPONSE`

The SDK never throws into your app. "No link" (`null`) and a failure both let your app carry on to its normal start screen. Failures are logged as warnings, and the last one is kept in `DLN.lastError` (`DLN.getLastError()` from Java) until the next successful call of the same kind (init, `checkDeferredDeepLink`, `findDeferredUser`):

```kotlin
DLN.lastError?.let { Log.w("MyApp", "DLN: ${it.code.value}") }
```

## Upgrading from 1.0.x

1.1 is source-compatible, and the old calls keep working:

- `DLN.getInstance().getDeferredDeepLink()` still returns the cached referrer result on every launch. Prefer `DLN.checkDeferredDeepLink()`.
- `DLN.getInstance().findDeferredUser()` still returns `MatchResponse?`. It now also decodes `matchQuality`, `deeplink.queryParams` and `deeplink.fullPath`. `findDeferredUserResult()` returns typed errors.
- `DLN.getInstance().parseDeepLink(url)`, which returns a `Pair`, is deprecated in favour of `DLN.parseDeepLink(url)`.
- `initializeSDK()` is deprecated because it is not public API. `DLN.init` runs init once.
- `hasDeepLinkToken()` now checks only the clipboard description, and returns true for any text or URL. It used to look for a `dln://` prefix that the web page never writes.

## Privacy

The SDK sends these to `/api/v1/sdk/match`:

- a synthetic user agent
- the OS version and `Build.MODEL`
- language and time zone
- screen size (dp) and density
- install and open times
- `ANDROID_ID`, which is scoped to the app-signing key. It is used to claim a click for this install, and is also sent on `/init` so monthly active users are counted per device (the server keeps only a hash)

`advertising_id` and `vendor_id` are always `null`.

The SDK stores only the first-launch flag, the referrer result and a copy of the last `/init` result (your public key, link domains and app info, no device data, so launches within 24 hours skip the network call), in `noBackupFilesDir`, which Auto Backup does not restore. Your API key is never logged.

## License

This SDK is proprietary software. All rights reserved.
