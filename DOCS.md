# DeepLinkNow Android SDK Documentation

## Overview

The DeepLinkNow Android SDK (`com.deeplinknow:dln-android`, class `com.deeplinknow.DLN`) does two jobs:

1. **Initialize**: validate your API key and account, and learn your app's link domains.
2. **Return the deferred deep link**: the path and query the user tapped before installing. It is read from the **Play Install Referrer** (exact), with **fingerprint matching** as a scored backup.

It also parses App Links that open an already-installed app, so deferred and direct opens route the same way. It is not an analytics or attribution SDK.

## Requirements

- Android API level 24 or higher
- Kotlin 1.9 or higher (Java callers are supported through callback overloads)

## Installation

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.1.0")
}
```

```xml
<dependency>
  <groupId>com.deeplinknow</groupId>
  <artifactId>dln-android</artifactId>
  <version>1.1.0</version>
  <type>aar</type>
</dependency>
```

The library manifest declares `android.permission.INTERNET`. The Install Referrer library adds `BIND_GET_INSTALL_REFERRER_SERVICE`. Consumer R8 rules for the Gson model classes are included.

## Initialization

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DLN.init(
            context = this,
            apiKey = "your-public-api-key",
            enableLogs = BuildConfig.DEBUG,   // debug logs under tag "DLN"; the key is never logged
            // optional:
            baseUrl = "https://deeplinknow.com",
            timeoutMs = 10_000,               // per request
            retryAttempts = 2,                // transient failures: network, timeout, 5xx, 429
            overrideScreenWidth = null,       // emulator testing only (dp)
            overrideScreenHeight = null,
            overridePixelRatio = null,
            onComplete = { result -> /* Result<DLNAppInfo>, main thread */ },
        )
    }
}
```

- `DLN.init` returns immediately. It stores only the application context and keeps a process-wide singleton.
- It calls `POST /api/v1/sdk/init` once and starts reading the Install Referrer.
- Calling it again with the same key reuses the running or completed init. A different key replaces the previous state.
- You can observe the outcome three ways:
  - the `onComplete` callback
  - `DLN.awaitInitialization()` (suspend, returns `Result<DLNAppInfo>`)
  - `DLN.isInitialized`
- `DLNAppInfo` has `appId`, `alias`, `name`, `linkDomains` (`<alias>.deeplinknow.com` plus verified custom domains), `accountStatus` and `creditsRemaining`.
- A non-https `baseUrl` is refused, except `localhost`, `127.0.0.1` and `10.0.2.2` for development.

## The deferred deep link: `checkDeferredDeepLink`

```kotlin
lifecycleScope.launch {
    val result = DLN.checkDeferredDeepLink(minConfidence = null, force = false)
    result.onSuccess { link ->
        if (link != null) {
            // link.path = "/products/123", link.queryParams["ref"] = QueryValue.Single("newsletter")
            navigate(link.path, link.queryParams)
        }
    }.onFailure { e ->
        val error = e as DLNException // e.code: NETWORK, INVALID_API_KEY, NOT_INITIALIZED, …
    }
}
```

The chain:

1. It waits for init. If init failed, it returns `NOT_INITIALIZED`, with the init error as the `cause`.
2. **Play Install Referrer.** The landing page puts `fp_id=<uuid>` (or `deeplink_id=<uuid>`) in the Play Store `referrer`. The SDK URL-decodes the referrer and reads those exact keys. It accepts only UUID values, and `fp_id` wins when both are present. It then calls `GET /api/v1/sdk/referrer-lookup`.
   - A hit returns `source = REFERRER`, `confidenceScore = 100` and `matchQuality = HIGH`.
   - If the referrer service is unavailable, it retries up to 3 connections with backoff.
3. **Fingerprint.** If the referrer step gives no link, it calls `POST /api/v1/sdk/match`. It takes the first candidate, in server order, that has a deep link and passes `minConfidence` when you set one.
   - The result keeps the server's `confidenceScore`, `matchQuality` (HIGH ≥ 75, MEDIUM ≥ 50, LOW otherwise) and `matchDetails`.
4. **Once per install.** After a link is returned, or every step definitively found nothing, a flag is stored and later calls resolve to `null` with no network access. `force = true` ignores the flag.
   - Errors never set the flag: network, timeouts, 401/403/429/5xx and malformed responses. The next call or launch retries.
   - The Android chain never reads the clipboard.

Concurrent calls share one run. From Java, use the callback overload:

```java
DLN.checkDeferredDeepLink(null, false, new DeferredDeepLinkCallback() {
    @Override public void onResult(DeferredDeepLink link, DLNException error) { /* main thread */ }
});
```

`DLN.resetDeferredDeepLink()` clears the first-launch flag and the cached referrer result, in memory and on disk. Use it for testing.

### `DeferredDeepLink`

| Field | Type | Meaning |
|---|---|---|
| `path` | `String` | `fullPath` up to the first `?`, not decoded, starts with `/` |
| `queryParams` | `Map<String, QueryValue>` | URLSearchParams semantics: `Single`, `Flag` (empty value), `Multiple` (repeated key). `QueryValue.toPlain()` gives `String`, `true` or `List<String>` |
| `fullPath` | `String` | Path and query as tapped (server `full_path`) |
| `url` | `String` | `target_url` if it is an absolute URL on your link domain, else `https://<primary domain><fullPath>` |
| `source` | `DeferredDeepLinkSource` | `REFERRER` or `FINGERPRINT` (`.value` gives `"referrer"` / `"fingerprint"`) |
| `confidenceScore` | `Double` | 0–100 |
| `matchQuality` | `MatchQuality` | `HIGH`, `MEDIUM` or `LOW` |
| `deeplinkId`, `targetUrl`, `campaignId`, `metadata`, `matchedAt`, `expiresAt` | | From the server |
| `matchDetails` | `MatchDetails?` | Fingerprint only |

DLN does not choose a threshold for you. Fingerprints are best effort: iOS hides real IPs and many devices share Wi-Fi. Use `minConfidence`, or inspect `matchQuality`, if a wrong guess is costly for your app.

## Errors

`DLNException(code, httpStatus, serverMessage)`. The `code` values, with the `.value` wire name shared with iOS and React Native:

| Code | When |
|---|---|
| `NOT_INITIALIZED` (`notInitialized`) | `DLN.init` not called, or init failed (`cause` = init error) |
| `INVALID_API_KEY` (`invalidApiKey`) | 401 |
| `ACCOUNT_INACTIVE` (`accountInactive`) | 403 "Account is not active" |
| `NO_CREDITS` (`noCredits`) | 403 "No credits remaining" |
| `RATE_LIMITED` (`rateLimited`) | 429 |
| `BAD_REQUEST` (`badRequest`) | 400, other 4xx, invalid `baseUrl` |
| `SERVER` (`server`) | 5xx |
| `NETWORK` (`network`) | Transport error, timeout, offline |
| `INVALID_RESPONSE` (`invalidResponse`) | Unparseable or unexpected body (e.g. a captive portal page) |

The SDK runs its background work in its own coroutine scope and never throws into your app.

## Lower-level APIs

| API | Notes |
|---|---|
| `DLN.findDeferredUserResult()` / `getInstance().findDeferredUserResult()` | `Result<MatchResponse>`: all fingerprint candidates, unfiltered, in server order. Each `Match` has `confidenceScore`, `matchQuality`, `matchDetails` and `deeplink` (`queryParams`, `fullPath`, `typedQueryParams`) |
| `getInstance().findDeferredUser()` | 1.0 API: `MatchResponse?` (null on any error) |
| `getInstance().getDeferredDeepLink()` | 1.0 API: the cached referrer `DeeplinkMatch?`, returned on every launch |
| `getInstance().getReferrerData()` | The raw referrer result (`referrerString`, `fpId`, `deeplinkId`, `deeplinkData`, `processedAt`) |
| `getInstance().checkClipboard()` | Returns the clipboard text only if, trimmed, it is exactly one http(s) URL on your link domains. Returns null without focus (Android 10+). On Android 12+ reading shows a system "pasted" toast, so call it only when you need it |
| `getInstance().hasDeepLinkToken()` | Checks the clip description only (no toast). True means it is worth calling `checkClipboard()` |
| `DLN.parseDeepLink(url)` | `ParsedDeepLink(path, queryParams, fullPath, url)` or null |
| `DLN.isValidDomain(host)` | The host rule below |
| `getInstance().parseDeepLink(url)` | Deprecated 1.0 API: `Pair<String, Map<String, String>>` (decoded path, first value of repeated keys) |

## Direct opens (App Links)

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="myapp.deeplinknow.com" />
        <!-- repeat <data android:host="go.myapp.com" /> for a verified custom domain -->
    </intent-filter>
</activity>
```

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
}

private fun handle(intent: Intent?) {
    val url = intent?.data?.toString() ?: return
    DLN.parseDeepLink(url)?.let { navigate(it.path, it.queryParams) }
}
```

Alternatively, register a `DeepLinkCallback` with `DLN.setDeepLinkCallback(...)` and call `DLN.handleDeepLink(url)`. `DeepLinkData` contains:

- `route`: the path without its leading `/`, decoded (1.0 behaviour)
- `params`: a single-valued map
- `path`: undecoded, with the leading `/`
- `queryParams`: typed values

A callback that throws does not crash the SDK.

### Host rule

- Hosts are lowercased, and a trailing `.` and any port are dropped.
- After a successful init, only `<alias>.deeplinknow.com` and your **verified** custom domains are accepted.
- Before init, any non-reserved `<alias>.deeplinknow.com` is accepted provisionally, and custom domains are rejected.
- These are always rejected:
  - the apex `deeplinknow.com`
  - reserved subdomains (`www`, `app`, `api`, `docs`, `m`, `mail`)
  - any `*.deeplink.now` host
  - other schemes
  - lookalikes such as `deeplinknow.com.evil.com` or `https://a.deeplinknow.com@evil.com/`

## Privacy

| Data | Purpose |
|---|---|
| Synthetic UA, OS version, `Build.MODEL`, language, time zone, screen size (dp) and density | Fingerprint matching against the click |
| `ANDROID_ID` (`device_id`) | Sent on `/match` to claim a click for this install and keep repeat calls idempotent. Also sent on `/init` so monthly active users are counted per device, not per IP; the server stores only a hash. It is app-signing-key scoped |
| Install Referrer string | Exact deferred link |

- `advertising_id` and `vendor_id` are always JSON `null`.
- The SDK does not read the Google Advertising ID, location, contacts, carrier or the installed-apps list.
- The server derives the IP from the connection.
- On device the SDK stores only the first-launch flag and the definitive referrer result. They live in `noBackupFilesDir`, so they are excluded from Auto Backup and device transfer.
- 1.0's `SharedPreferences("dln_referrer")` is migrated once and deleted.

## Emulator testing

```kotlin
DLN.init(applicationContext, "your-api-key", enableLogs = true,
    overrideScreenWidth = 393, overrideScreenHeight = 852, overridePixelRatio = 2.75f)
```

## Support

- Email: support@deeplinknow.com
