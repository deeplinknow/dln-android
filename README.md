# DeepLinkNow Android SDK

The DeepLinkNow Android SDK provides deep linking functionality for Android applications, allowing you to track, match, and handle deep links with ease.

## Installation

### Maven

```xml
<dependency>
  <groupId>com.deeplinknow</groupId>
  <artifactId>dln-android</artifactId>
  <version>1.0.11</version>
  <type>aar</type>
</dependency>
```

### Gradle

Add the DeepLinkNow SDK to your project by including it in your app's `build.gradle` file:

```gradle
dependencies {
    implementation 'com.deeplinknow:dln-android:1.0.11'
}
```

Or if you're using Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.0.11")
}
```

## Usage

### Initialization

Initialize the SDK in your Application class or main Activity:

```kotlin
import com.deeplinknow.DLN

// In your Application class or main Activity
DLN.init(
    context = context,
    apiKey = "your-api-key-here",
    enableLogs = BuildConfig.DEBUG
)
```

### Finding Deferred Users

To find deferred users and match deep links:

```kotlin
// Using coroutines
val response = DLN.getInstance().findDeferredUser()
response?.matches?.forEach { match ->
    // Access match properties
    val confidenceScore = match.confidenceScore
    val deeplink = match.deeplink
    val matchDetails = match.matchDetails

    // Deeplink information
    deeplink?.let {
        println("Target URL: ${it.targetUrl}")
        println("Campaign ID: ${it.campaignId}")
        println("Matched At: ${it.matchedAt}")
        println("Expires At: ${it.expiresAt}")
    }

    // Match details
    matchDetails?.let {
        println("IP Match: ${it.ipMatch.matched} (Score: ${it.ipMatch.score})")
        println("Device Match: ${it.deviceMatch.matched} (Score: ${it.deviceMatch.score})")
        println("Locale Match: ${it.localeMatch.matched} (Score: ${it.localeMatch.score})")
    }
}
```

### Checking Clipboard for Deep Links

To check if the clipboard contains a deep link:

```kotlin
val clipboardContent = DLN.getInstance().checkClipboard()
clipboardContent?.let {
    println("Found deep link in clipboard: $it")
}
```

### Parsing Deep Links

To parse and validate deep links:

```kotlin
val url = "https://deeplinknow.com/your-path?param1=value1"
val (path, parameters) = DLN.getInstance().parseDeepLink(url) ?: return

println("Path: $path")
println("Parameters: $parameters")
```

## Configuration

The SDK can be configured during initialization:

```kotlin
DLN.init(
    context = context,
    apiKey = "your-api-key-here",
    enableLogs = true // Enable debug logging
)
```

## Example App

Check out our [example app](example/) for a complete implementation of the DeepLinkNow SDK, including:

- SDK initialization
- Finding deferred users
- Handling deep links
- Clipboard integration
- Match result display

## Requirements

- Android API level 24 or higher
- Kotlin 1.9.0 or higher
- AndroidX

## License

This SDK is proprietary software. All rights reserved.
