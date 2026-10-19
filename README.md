# DeepLinkNow Android SDK

Deferred deep linking for Android. When a user taps a DeepLinkNow link before installing your app, the SDK hands your app the path and query they tapped on first launch. It reads the **Play Install Referrer** (exact) and falls back to **fingerprint matching** (scored). It also validates and parses App Links that open an installed app.

DeepLinkNow is not an analytics or attribution SDK. It never reads the advertising ID and needs no tracking consent. You forward the result to whatever analytics you use.

## Installation

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.2.0")
}
```

```gradle
dependencies {
    implementation 'com.deeplinknow:dln-android:1.2.0'
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
