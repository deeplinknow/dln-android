# Changelog

All notable changes to the DeepLinkNow Android SDK (`com.deeplinknow:dln-android`). iOS, Android and React Native share one major.minor version (SDK-84).

## 1.3.0 (2026-10-21)

- **Version re-sync.** iOS, Android and React Native all ship as 1.3.0, so one number names one feature set again (React Native had moved to 1.2.1 on its own). React Native 1.3.0 adds an Expo Router helper and pins `com.deeplinknow:dln-android:1.3.0`.
- No API or behaviour changes from 1.2.0. `DLN.SDK_VERSION` is `"1.3.0"`, so `/init` reports `android/1.3.0` (FP-21).
- Packaging: the repo now includes the Apache-2.0 `LICENSE` the POM declares, and the POM's description, project URL and SCM links point at `github.com/deeplinknow/dln-android` (they pointed at a personal fork).

## 1.2.0 (2026-10-19)

- `DLN.awaitReferrer()`, `DLN.lastError`, re-init that compares the whole config and keeps the deep link callback, callbacks delivered exactly once, `PERMISSION_ERROR` treated as definitive and a 5-second bound per referrer attempt. See `DOCS.md`.

## 1.1.0 (2026-10-19)

- `checkDeferredDeepLink(options)` runs the Install Referrer and fingerprint chain in one call. See `DOCS.md`.
