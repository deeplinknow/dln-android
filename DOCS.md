# DeepLinkNow Android SDK Documentation

## Overview

DeepLinkNow Android SDK provides lightweight deep linking and attribution capabilities for Android applications, with a focus on deferred deep linking functionality. This SDK allows you to track, match, and handle deep links with ease.

## Requirements

- Android API level 24 or higher
- Kotlin 1.9.0 or higher
- AndroidX

## Installation

### Maven

```xml
<dependency>
  <groupId>com.deeplinknow</groupId>
  <artifactId>dln-android</artifactId>
  <version>1.0.15</version>
  <type>aar</type>
</dependency>
```

### Gradle

Add to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'com.deeplinknow:dln-android:1.0.15'
}
```

Or if using Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.0.15")
}
```

## Getting Started

### Initialize the SDK

First, initialize the SDK in your Application class or main Activity. You'll need an API key from your DeepLinkNow dashboard:

```kotlin
import com.deeplinknow.DLN

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Basic initialization
        DLN.init(
            context = this,
            apiKey = "your-api-key-here"
        )

        // With additional configuration
        DLN.init(
            context = this,
            apiKey = "your-api-key-here",
            enableLogs = BuildConfig.DEBUG,  // Enable logs in debug builds
            overrideScreenWidth = null,      // Optional - for testing
            overrideScreenHeight = null,     // Optional - for testing
            overridePixelRatio = null        // Optional - for testing
        )
    }
}
```

The SDK will automatically initialize and fetch your app's custom domains during this process.

## Core Features

### Finding Deferred Users

The primary feature of DeepLinkNow is identifying users who have clicked on a deep link before installing your app. This is typically called in your app's first activity:

```kotlin
// Using Kotlin Coroutines
lifecycleScope.launch {
    val response = DLN.getInstance().findDeferredUser()

    response?.matches?.forEach { match ->
        // Access match properties
        val confidenceScore = match.confidenceScore
        val deeplink = match.deeplink
        val matchDetails = match.matchDetails

        // Process the match information
        // ...
    }
}
```

### Understanding Match Results

The SDK returns up to 5 potential matches, ranked by confidence score. Each match contains:

1. **Deeplink**: Contains the target URL, metadata, campaign ID, and timestamp information
2. **Confidence Score**: A percentage indicating how confident the match is
3. **Match Details**: Shows which parameters matched between the fingerprints:
   - IP Match: Whether the IP address matched
   - Device Match: OS version, device model, hardware fingerprint
   - Time Proximity: How close in time the click and app open were
   - Locale Match: Language and timezone match

Example of processing the match details:

```kotlin
match.deeplink?.let { deeplink ->
    // Access deep link information
    val targetUrl = deeplink.targetUrl
    val metadata = deeplink.metadata
    val campaignId = deeplink.campaignId
    val matchedAt = deeplink.matchedAt
    val expiresAt = deeplink.expiresAt

    // Take action based on the deeplink
    navigateToContent(targetUrl)
}

match.matchDetails?.let { details ->
    // Examine confidence factors
    val ipMatched = details.ipMatch.matched
    val ipScore = details.ipMatch.score

    val deviceMatched = details.deviceMatch.matched
    val deviceScore = details.deviceMatch.score

    val localeMatched = details.localeMatch.matched
    val localeScore = details.localeMatch.score

    val timeDifference = details.timeProximity.timeDifferenceMinutes
    val timeScore = details.timeProximity.score

    // You can use these details to determine your confidence threshold
    if (match.confidenceScore > 0.7) {
        // High confidence match
    } else if (match.confidenceScore > 0.4) {
        // Medium confidence match
    } else {
        // Low confidence match
    }
}
```

### Checking Clipboard for Deep Links

If you don't find a fingerprinted user through the `findDeferredUser()` method, you can check the clipboard for a copied deep link. Note that you should request clipboard permission from the user before doing this:

```kotlin
// Make sure to request proper clipboard permissions in your app
val clipboardContent = DLN.getInstance().checkClipboard()
clipboardContent?.let { deepLinkUrl ->
    // Process the deep link found in the clipboard
    val (path, params) = DLN.getInstance().parseDeepLink(deepLinkUrl) ?: return

    // Use the path and parameters to route the user
    navigateToDestination(path, params)
}
```

### Parsing Deep Links

The SDK provides tools to parse and validate deep links:

```kotlin
val url = "https://deeplinknow.com/your-path?param1=value1&param2=value2"
val result = DLN.getInstance().parseDeepLink(url)

result?.let { (path, parameters) ->
    // Process the path and parameters
    println("Path: $path")
    println("Parameters: $parameters")

    // Use the parameters to customize the user experience
    parameters["campaign"]?.let { campaignId ->
        // Track the campaign
    }

    parameters["referrer"]?.let { referrer ->
        // Track the referrer
    }
}
```

### Deep Link Interception

The SDK provides automatic deep link interception for when your app is opened via a deep link. This feature automatically captures and processes deep links from deeplinknow.com domains or your custom domains.

#### Setting Up Deep Link Callback

First, implement the `DeepLinkCallback` interface:

```kotlin
import com.deeplinknow.DeepLinkCallback
import com.deeplinknow.DeepLinkData

class MainActivity : AppCompatActivity(), DeepLinkCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register the deep link callback
        DLN.setDeepLinkCallback(this)

        // Handle deep link if app was opened from one
        handleIncomingDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingDeepLink(intent)
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            val url = uri.toString()

            // Use DLN SDK to handle the deep link
            DLN.handleDeepLink(url)
        }
    }

    // Deep link callback implementation
    override fun onDeepLinkOpen(deepLinkData: DeepLinkData) {
        val route = deepLinkData.route
        val params = deepLinkData.params

        // Handle the deep link based on route and params
        when (route) {
            "product/detail" -> {
                val productId = params["id"]
                // Navigate to product detail
            }
            "user/profile" -> {
                val userId = params["user_id"]
                // Navigate to user profile
            }
            else -> {
                // Handle other routes
            }
        }
    }
}
```

#### Deep Link Data Structure

The `DeepLinkData` object contains:

- `route`: The path portion of the URL (without leading slash)
- `params`: A map of query parameters (all values are strings)

For example, the URL `https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world` would produce:

```kotlin
DeepLinkData(
    route = "this_is_a_test_url/anything/else/here",
    params = mapOf(
        "blah" to "123",
        "test" to "true",
        "hello" to "world"
    )
)
```

#### Manual Deep Link Handling

You can also manually handle deep links:

```kotlin
val url = "https://yourdomain.deeplinknow.com/path/to/content?param1=value1"
val handled = DLN.handleDeepLink(url)

if (handled) {
    // Deep link was valid and processed
    // Your callback will be triggered
} else {
    // Deep link was not from a valid domain
}
```

#### Android Manifest Configuration

Make sure your Activity is configured to handle deep links in your `AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- Handle deep links -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="https"
            android:host="yourdomain.deeplinknow.com" />
    </intent-filter>
</activity>
```

Replace `yourdomain.deeplinknow.com` with your actual deep link domain.

#### Domain Validation

The SDK automatically validates deep link domains using the following rules:

1. **DeepLinkNow domains**: Any subdomain of `deeplinknow.com` (e.g., `yourapp.deeplinknow.com`, `test123.deeplinknow.com`)
2. **Alternative domains**: Any subdomain of `deeplink.now`
3. **Custom domains**: Domains configured in your DeepLinkNow dashboard and returned in the init response

Examples of valid domains:

- `https://yourapp.deeplinknow.com/path`
- `https://deeplinknow.com/path`
- `https://custom.deeplink.now/path`
- Custom domains configured in your account

Invalid domains will be rejected and won't trigger the deep link callback.

## Integration Examples

### Typical App Flow

Here's a typical flow for integrating DeepLinkNow in your application:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check for deferred deep links
        checkForDeferredLinks()
    }

    private fun checkForDeferredLinks() {
        lifecycleScope.launch {
            // Try to find a deferred user first
            val response = DLN.getInstance().findDeferredUser()
            var foundDeepLink = false

            // Process any matches
            response?.matches?.firstOrNull { match ->
                match.confidenceScore > 0.5 && match.deeplink != null
            }?.let { highConfidenceMatch ->
                highConfidenceMatch.deeplink?.let { deeplink ->
                    handleDeepLink(deeplink.targetUrl)
                    foundDeepLink = true
                }
            }

            // If no deferred user was found, check the clipboard (with permission)
            if (!foundDeepLink) {
                DLN.getInstance().checkClipboard()?.let { clipboardUrl ->
                    handleDeepLink(clipboardUrl)
                }
            }
        }
    }

    private fun handleDeepLink(url: String) {
        DLN.getInstance().parseDeepLink(url)?.let { (path, params) ->
            when (path) {
                "/products" -> {
                    val productId = params["id"]
                    navigateToProduct(productId)
                }
                "/categories" -> {
                    val categoryId = params["id"]
                    navigateToCategory(categoryId)
                }
                "/promo" -> {
                    val promoCode = params["code"]
                    applyPromoCode(promoCode)
                }
                // Handle other paths
            }
        }
    }
}
```

### Handling Direct Deep Links

For handling direct deep links (when the app is opened from a link), configure your `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" />
        <data android:host="yourdomain.deeplinknow.com" />
    </intent-filter>
</activity>
```

Then handle the incoming intent:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIncomingIntent(intent)
}

private fun handleIncomingIntent(intent: Intent) {
    if (intent.action == Intent.ACTION_VIEW) {
        intent.data?.toString()?.let { url ->
            handleDeepLink(url)
        }
    }
}
```

## Advanced Configuration

### Debug Logging

Enable logs to help with debugging:

```kotlin
DLN.init(
    context = applicationContext,
    apiKey = "your-api-key",
    enableLogs = true  // Enable debug logs
)
```

### Testing with Emulators

For testing in emulators, you can override screen dimensions and pixel ratio:

```kotlin
DLN.init(
    context = applicationContext,
    apiKey = "your-api-key",
    enableLogs = true,
    overrideScreenWidth = 360,  // Standard phone width
    overrideScreenHeight = 800, // Standard phone height
    overridePixelRatio = 2.0f   // Standard pixel density
)
```

## Domain Validation

The SDK automatically validates deep links against:

- deeplinknow.com
- deeplink.now
- Your app's verified custom domains (configured in the dashboard)

These domains are loaded during SDK initialization.

## Best Practices

1. **Call `findDeferredUser()` Early**: Make this call as early as possible in your app lifecycle, typically in the first activity.

2. **Handle Multiple Confidence Levels**: Consider different actions based on the confidence score of matches.

3. **Respect User Privacy**: Always request permission before accessing clipboard data.

4. **Error Handling**: Add proper error handling for network requests and parsing.

5. **Testing**: Test your implementation with both real devices and emulators to ensure it works correctly.

## Support

- Email: support@deeplinknow.com
- Documentation: [docs.deeplinknow.com](https://docs.deeplinknow.com)
