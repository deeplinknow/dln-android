# DeepLinkNow Android example

A single-activity app that shows the whole DeepLinkNow Android integration: `app/src/main/java/com/example/dlnwrapper/MainActivity.kt` (package `com.deeplinknow.example`).

1. `DLN.init(context, apiKey)` + `DLN.awaitInitialization()` at startup. The init result is a `Result<DLNAppInfo>`; a failure (`DLNException`, with `code`, `httpStatus`, `serverMessage`) is shown on screen. A real app would usually call `DLN.init` in `Application.onCreate`.
2. `DLN.checkDeferredDeepLink(minConfidence = …)` once, on first launch. The screen shows `path`, `queryParams`, `fullPath`, `source`, `confidenceScore` and `matchQuality`. Android reads the Play Install Referrer first (exact), then falls back to fingerprint matching.
3. `DLN.parseDeepLink(url)` for App Links that open the installed app, in `onCreate` and `onNewIntent`.

For testing, the screen also has a **minConfidence** choice (Off / 30 / 50 / 75), **Check again (force)** and **Reset deferred link** (`DLN.resetDeferredDeepLink()`).

Replace `API_KEY` in `MainActivity.kt` with your app's key. `test_api_key` is DeepLinkNow's demo key and returns sample data.

## Build

```bash
cd packages/dln-android/example
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug    # any JDK 17+
```

Or open `packages/dln-android/example` in Android Studio.

## App Links

`app/src/main/AndroidManifest.xml` has an `autoVerify` intent filter for `https://jvgtest123.deeplinknow.com`. Change the host to `{alias}.deeplinknow.com` (and add one `<data android:host="…"/>` per custom domain). In the dashboard, set the package name `com.deeplinknow.example` and your signing certificate's SHA-256 so DeepLinkNow serves a matching `assetlinks.json`.

Test without a browser:

```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d "https://jvgtest123.deeplinknow.com/products/123?ref=nl" com.deeplinknow.example
```

To test the Install Referrer path you need an install from the Play Store (internal testing track). On an emulator or sideloaded build the referrer is empty and the SDK falls back to fingerprinting.

## Local SDK (default) vs published SDK

`app/build.gradle.kts` depends on `com.deeplinknow:dln-android:1.1.0`. While the SDK is unpublished, `settings.gradle.kts` replaces that dependency with the local source in the parent directory (`packages/dln-android`) through a composite build:

```kotlin
// settings.gradle.kts
val useLocalSdk = true   // SWITCH
```

After `com.deeplinknow:dln-android:1.1.0` is published, set `useLocalSdk = false` to build against the published artifact from Maven Central.

The example uses the same Android Gradle Plugin version as the SDK (8.3.2). Gradle doesn't allow two AGP versions in one composite build, so if you upgrade AGP in one, upgrade it in the other.
