#!/bin/bash

# DeepLinkNow Android Example App - Deep Link Testing Script
# This script uses ADB to test various deep link scenarios

echo "🔗 DeepLinkNow Deep Link Testing Script"
echo "========================================"

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    echo "❌ ADB not found. Please install Android SDK Platform-tools."
    exit 1
fi

# Check if device is connected
if [ -z "$(adb devices | grep -v 'List of devices attached')" ]; then
    echo "❌ No Android device connected. Please connect a device or start an emulator."
    exit 1
fi

PACKAGE_NAME="com.example.dlnwrapper"

echo "📱 Testing with package: $PACKAGE_NAME"
echo ""

# Test 1: The user's specific failing URL
echo "Test 1: User's specific URL that was failing"
echo "URL: https://jvgtest123.deeplinknow.com/this_is_a_test_url?params=123&test=true&hello=world"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/this_is_a_test_url?params=123&test=true&hello=world" \
    $PACKAGE_NAME

echo "✅ Test 1 completed. Check the app for deep link handling."
echo ""
sleep 3

# Test 2: The main test URL from requirements
echo "Test 2: Extended test URL"
echo "URL: https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/this_is_a_test_url/anything/else/here?blah=123&test=true&hello=world" \
    $PACKAGE_NAME

echo "✅ Test 2 completed. Check the app for deep link handling."
echo ""
sleep 3

# Test 3: Product detail route
echo "Test 3: Product detail route"
echo "URL: https://jvgtest123.deeplinknow.com/product/detail?id=12345&category=electronics"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/product/detail?id=12345&category=electronics" \
    $PACKAGE_NAME

echo "✅ Test 3 completed. Check the app for product detail handling."
echo ""
sleep 3

# Test 4: User profile route
echo "Test 4: User profile route"
echo "URL: https://jvgtest123.deeplinknow.com/user/profile?user_id=abc123&tab=settings"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/user/profile?user_id=abc123&tab=settings" \
    $PACKAGE_NAME

echo "✅ Test 4 completed. Check the app for user profile handling."
echo ""
sleep 3

# Test 5: Promotion offer route
echo "Test 5: Promotion offer route"
echo "URL: https://jvgtest123.deeplinknow.com/promotion/offer?code=SAVE20&discount=20"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/promotion/offer?code=SAVE20&discount=20" \
    $PACKAGE_NAME

echo "✅ Test 5 completed. Check the app for promotion handling."
echo ""
sleep 3

# Test 6: Generic route (should use default handler)
echo "Test 6: Generic route"
echo "URL: https://jvgtest123.deeplinknow.com/unknown/route?param1=value1&param2=value2"
adb shell am start -W -a android.intent.action.VIEW \
    -d "https://jvgtest123.deeplinknow.com/unknown/route?param1=value1&param2=value2" \
    $PACKAGE_NAME

echo "✅ Test 6 completed. Check the app for generic route handling."
echo ""

echo "🎉 All deep link tests completed!"
echo ""
echo "💡 Tips:"
echo "- Open the example app and check the log output"
echo "- Each test should trigger the onDeepLinkOpen callback"
echo "- The app should show route and parameter information"
echo "- Initialize the DLN SDK first for full functionality"
echo ""
echo "🔧 To test manually:"
echo "adb shell am start -W -a android.intent.action.VIEW -d \"YOUR_URL_HERE\" $PACKAGE_NAME" 