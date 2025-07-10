# DeepLinkNow Android Example App

This example app demonstrates how to use the DeepLinkNow Android SDK with comprehensive deep link handling functionality.

## Features Demonstrated

### 1. SDK Initialization

- Initialize the DLN SDK with API key
- Enable logging for debugging
- Verify successful initialization

### 2. Deep Link Interception

- Automatic deep link handling when app is opened
- Callback-based deep link processing
- Route and parameter extraction
- Domain validation

### 3. Manual URL Parsing

- Parse deep link URLs manually
- Extract paths and query parameters
- Validate domains

### 4. Test Scenarios

- Test various deep link patterns
- Simulate different route handlers
- Error handling for invalid domains

## How to Use

### 1. Initialize the SDK

1. Tap "Init DLN" button to initialize the SDK
2. Watch the log output for initialization steps
3. Button will change to "Initialized!" when complete

### 2. Test Deep Link Handling

1. Tap "Test Deep Link Handling" to simulate various deep link scenarios
2. Watch the log output to see how different URLs are processed
3. Observe the callback handling for different routes

### 3. Test Manual Parsing

1. Tap "Test Manual URL Parsing" to test URL parsing without triggering callbacks
2. See how valid and invalid URLs are processed
3. Examine the extracted paths and parameters

### 4. Test Real Deep Links

1. Set up your Android manifest to handle deep links (see below)
2. Use ADB to simulate deep link opening:
   ```bash
   adb shell am start -W -a android.intent.action.VIEW -d "https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world" com.example.dlnwrapper
   ```
3. Watch the app handle the deep link automatically

## Android Manifest Configuration

Make sure your `AndroidManifest.xml` includes:

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
            android:host="jvgtest123.deeplinknow.com" />
    </intent-filter>
</activity>
```

## Deep Link Examples

The app handles these route patterns:

### Test Route

- **URL**: `https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world`
- **Route**: `this_is_a_test_url/anything/else/here`
- **Parameters**: `{blah: "123", test: "true", hello: "world"}`

### Product Detail

- **URL**: `https://jvgtest123.deeplinknow.com/product/detail?id=12345&category=electronics`
- **Route**: `product/detail`
- **Parameters**: `{id: "12345", category: "electronics"}`

### User Profile

- **URL**: `https://jvgtest123.deeplinknow.com/user/profile?user_id=abc123&tab=settings`
- **Route**: `user/profile`
- **Parameters**: `{user_id: "abc123", tab: "settings"}`

### Promotion Offer

- **URL**: `https://jvgtest123.deeplinknow.com/promotion/offer?code=SAVE20&discount=20`
- **Route**: `promotion/offer`
- **Parameters**: `{code: "SAVE20", discount: "20"}`

## Log Output

The app provides detailed logging in the UI showing:

- 🚀 Initialization steps
- 🔗 Deep link processing
- 📋 URL parsing results
- ✅ Success indicators
- ❌ Error messages
- 📊 Parameter extraction

## Clearing Results

Use the "Clear All Results" button to:

- Clear all displayed results
- Reset the log output
- Hide result text views
- Clear internal data

## Implementation Details

The example implements:

- `DeepLinkCallback` interface for handling deep links
- Route-based navigation patterns
- Parameter extraction and validation
- Comprehensive error handling
- UI feedback for all operations

See `MainActivity.kt` for the complete implementation.
