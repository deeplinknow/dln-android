# Android App Link Troubleshooting Guide

## Problem: Deep links open in browser instead of app

When you click a deep link (e.g., `https://yourdomain.deeplinknow.com/path`) and it opens in the browser instead of your app, this indicates an **Android App Link verification failure**.

## Root Causes

The most common issues are:

1. **Certificate fingerprint mismatch** (90% of cases)
2. **Incorrect `assetlinks.json` format**
3. **`assetlinks.json` not accessible via HTTPS**
4. **Missing `android:autoVerify="true"` in AndroidManifest**
5. **Testing on emulator instead of real device**
6. **App not reinstalled after configuration changes**

## Step-by-Step Troubleshooting

### 1. Verify Certificate Fingerprints

**This is the most common issue.** The SHA256 fingerprints in your `assetlinks.json` must exactly match your app's signing certificate.

#### Get your app's fingerprint:

**From APK:**

```bash
keytool -printcert -jarfile your-app.apk | grep SHA256
```

**From keystore:**

```bash
# For debug builds
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey

# For release builds
keytool -list -v -keystore your-release.keystore -alias your-alias
```

#### Use our helper script:

```bash
chmod +x verify-fingerprints.sh
./verify-fingerprints.sh your-app.apk
# or
./verify-fingerprints.sh ~/.android/debug.keystore androiddebugkey
```

### 2. Check `assetlinks.json` Format

Your `assetlinks.json` should look exactly like this:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.yourapp.package",
      "sha256_cert_fingerprints": [
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
      ]
    }
  }
]
```

**Common formatting mistakes:**

- Missing square brackets `[]` around the array
- Incorrect property names (e.g., `package_name` not `packageName`)
- Missing fingerprint colons
- Wrong namespace (must be `android_app`)

### 3. Verify `assetlinks.json` is Accessible

Test that your file is properly served:

```bash
curl -v "https://yourdomain.deeplinknow.com/.well-known/assetlinks.json"
```

**Requirements:**

- Must be accessible via HTTPS (not HTTP)
- Must return `Content-Type: application/json`
- Must return HTTP 200 status
- Must be accessible without authentication

### 4. Check AndroidManifest.xml Configuration

Your intent filter must include `android:autoVerify="true"`:

```xml
<activity android:name=".MainActivity">
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

**Common mistakes:**

- Missing `android:autoVerify="true"`
- Incorrect host name
- Missing categories
- Wrong scheme (must be `https`)

### 5. Test on Real Device

**App Links don't work reliably on emulators.** Always test on a physical device.

### 6. Clear App Data and Reinstall

Android caches App Link verification results. After making changes:

1. Uninstall the app completely
2. Clear browser cache (if testing in browser)
3. Reinstall the app
4. Test the deep link

### 7. Check App Link Verification Status

You can check if Android has verified your app links:

```bash
# Check verification status
adb shell pm get-app-links com.yourapp.package

# Reset verification (Android 12+)
adb shell pm reset-app-links com.yourapp.package
```

### 8. Test with Google's Tool

Use Google's Digital Asset Links tool to verify your configuration:
https://developers.google.com/digital-asset-links/tools/generator

## Advanced Debugging

### Enable App Link Verification Logs

On a debug device:

```bash
adb shell setprop log.tag.IntentFilterIntentSvc VERBOSE
adb shell setprop log.tag.IntentFilterIntentOp VERBOSE
adb logcat | grep -i "intent\|link\|verify"
```

### Manual Verification Test

Force Android to verify your domain:

```bash
adb shell am start \
  -W -a android.intent.action.VIEW \
  -d "https://yourdomain.deeplinknow.com/test" \
  com.yourapp.package
```

## Testing Checklist

Before concluding that App Links are broken, verify:

- [ ] Using a real Android device (not emulator)
- [ ] App is completely uninstalled and reinstalled
- [ ] Certificate fingerprints match exactly
- [ ] `assetlinks.json` is accessible via HTTPS
- [ ] `AndroidManifest.xml` has `android:autoVerify="true"`
- [ ] Domain matches exactly in both files
- [ ] Package name matches exactly
- [ ] Testing with a fresh browser session

## Common Error Messages

**"No app found to handle this link"**

- App not installed or verification failed
- Check all steps above

**"Choose an app to open this link"**

- Multiple apps can handle the link
- Your app is listed but not set as default
- This is actually success - your app is being recognized!

**Browser opens instead of app**

- Verification failed
- Most likely certificate fingerprint mismatch

## Debug Commands Reference

```bash
# Check app link verification status
adb shell pm get-app-links com.yourapp.package

# Reset app link verification
adb shell pm reset-app-links com.yourapp.package

# Test specific URL
adb shell am start -W -a android.intent.action.VIEW -d "https://yourdomain.deeplinknow.com/test"

# Check certificate fingerprint from APK
keytool -printcert -jarfile app.apk | grep SHA256

# Check certificate fingerprint from keystore
keytool -list -v -keystore keystore.jks -alias keyalias

# Test assetlinks.json accessibility
curl -v "https://yourdomain.deeplinknow.com/.well-known/assetlinks.json"
```

## Still Having Issues?

1. **Double-check fingerprints** - This is the #1 cause of issues
2. **Test on a real device** - Emulators are unreliable
3. **Reinstall the app** - Android caches verification results
4. **Check our example app** - See `dln-android/example/` for a working implementation

If you're still having trouble, please provide:

- Your `assetlinks.json` content
- Your `AndroidManifest.xml` intent filter
- The output of `keytool -printcert -jarfile your-app.apk`
- Whether you're testing on a real device or emulator
