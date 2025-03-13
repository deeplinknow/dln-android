# DeepLinkNow Android SDK

The DeepLinkNow Android SDK provides deep linking functionality for Android applications, allowing you to track, match, and handle deep links with ease.

## Installation

### Maven

```xml
<dependency>
  <groupId>com.deeplinknow</groupId>
  <artifactId>dln-android</artifactId>
  <version>1.0.0</version>
  <type>aar</type>
</dependency>
```

### Gradle

Add the DeepLinkNow SDK to your project by including it in your app's `build.gradle` file:

```gradle
dependencies {
    implementation 'com.deeplinknow:dln-android:1.0.0'
}
```

Or if you're using Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.deeplinknow:dln-android:1.0.0")
}
```

## Usage

### Initialization

Initialize the SDK in your Application class or main Activity:

```kotlin
// Kotlin
import com.deeplinknow.DeepLinkNow

// In your Application class or main Activity
DeepLinkNow.initialize(context, "your-api-key-here")
```

```java
// Java
import com.deeplinknow.DeepLinkNow;

// In your Application class or main Activity
DeepLinkNow.initialize(context, "your-api-key-here");
```
