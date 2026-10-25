# Changelog

All notable changes to the DeepLinkNow Android SDK (`com.deeplinknow:dln-android`). iOS, Android and React Native share one major.minor version (SDK-84).

## 1.4.1 (2026-10-25)

Fixes. No API changes.

- **A re-created activity no longer delivers its old link again** (SDK-88, AND-41). When Android killed the process and the user came back through Recents (or with "Don't keep activities", or after low memory), the activity got its original intent back and `handleIntent(intent)` in `onCreate` delivered the open a second time. `DLN.init` now watches activity creation and marks the intent of an activity created from saved state as handled. Apps that call `DLN.init` in `Application.onCreate` need no change; the README now also shows `if (savedInstanceState == null) DLN.handleIntent(intent)`.
- **One deferred chain for every concurrent caller** (SDK-27, SDK-91, AND-42). `checkDeferredDeepLink(minConfidence = 50)` running next to `onLink`'s default check started two `/match` chains, and the `onLink` handler could miss a link the other run found. Concurrent callers now share one run and each applies its own `minConfidence`; `onLink` gets the link if the app's own call found one it accepts.
- `DLN.SDK_VERSION` is `"1.4.1"`.

## 1.4.0 (2026-10-25)

One link object for every open (SDK-86 to SDK-91, AND-35 to AND-40). Additive: every 1.3.0 call compiles and behaves as before.

- **`DLNLink`**: every `DeferredDeepLink` field plus `type` (`DLNLinkType.DEFERRED` / `DIRECT`). `source` is a `DLNLinkSource`: the deferred `REFERRER`, `CLIPBOARD`, `FINGERPRINT`, plus `APP_LINK` (https on the app's domains) and `SCHEME` (the app's custom scheme). `DeferredDeepLink.source` keeps its three values.
- **`DLN.onLink(options) { link -> }`** (and a Java `DLNLinkHandler` overload): one handler for deferred and direct opens, on the main thread. Registering it runs the deferred check once per process; links that arrived before a handler are replayed to it once (deferred first). Returns a `DLNLinkSubscription`. The handler carries over when `DLN.init` replaces the instance.
- **`DLN.handleIntent(intent): Boolean`** from `onCreate` and `onNewIntent`. The same intent (or the same URL within 3 seconds) is delivered once, and an intent relaunched from Recents isn't delivered again. A custom scheme counts as the app's when the manifest declares it, or when it's in `DLNLinkOptions.schemes`.
- **Hold**: `DLNLinkOptions(hold = true)`, `DLN.pendingLinks` (the latest deferred and the latest direct link, deferred first), `DLN.pendingLink` (the first of them), `DLN.releasePendingLink()` (delivers them and ends the hold), `DLN.clearPendingLink()` (drops them, keeps holding).
- **Resolve**: `DLNLinkOptions(resolve = true)` and `DLN.resolveLink(url)` call `GET /api/v1/sdk/resolve` for App Links (one attempt, within the configured timeout; a 404 or error delivers the link unresolved).
- **Parsing** strips a `dln` fragment parameter (`#dln=<click id>`) from `url` (SDK-92). `fullPath`, `path` and `queryParams` never included the fragment.
- `setDeepLinkCallback` / `handleDeepLink(url)` are unchanged and separate from `onLink` / `handleIntent`.

## 1.3.0 (2026-10-21)

- **Version re-sync.** iOS, Android and React Native all ship as 1.3.0, so one number names one feature set again (React Native had moved to 1.2.1 on its own). React Native 1.3.0 adds an Expo Router helper and pins `com.deeplinknow:dln-android:1.3.0`.
- No API or behaviour changes from 1.2.0. `DLN.SDK_VERSION` is `"1.3.0"`, so `/init` reports `android/1.3.0` (FP-21).
- Packaging: the repo now includes the Apache-2.0 `LICENSE` the POM declares, and the POM's description, project URL and SCM links point at `github.com/deeplinknow/dln-android` (they pointed at a personal fork).

## 1.2.0 (2026-10-19)

- `DLN.awaitReferrer()`, `DLN.lastError`, re-init that compares the whole config and keeps the deep link callback, callbacks delivered exactly once, `PERMISSION_ERROR` treated as definitive and a 5-second bound per referrer attempt. See `DOCS.md`.

## 1.1.0 (2026-10-19)

- `checkDeferredDeepLink(options)` runs the Install Referrer and fingerprint chain in one call. See `DOCS.md`.
