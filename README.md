# MetaRouter Android SDK

[![CI](https://github.com/metarouterio/android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/metarouterio/android-sdk/actions/workflows/ci.yml)

Native Kotlin/Android SDK for MetaRouter analytics platform.

## Status

The SDK foundation is complete with all core functionality implemented and tested.

### Completed Features

- ✅ **Core Architecture**: MetaRouter singleton facade with proxy pattern
- ✅ **Identity Management**: Persistent storage for anonymousId, userId, groupId
- ✅ **Event Processing**: Type-safe event models with enrichment pipeline
- ✅ **Networking**: OkHttp-based client with circuit breaker and exponential backoff
- ✅ **Dispatcher**: Coroutine-based batch processing with configurable flush intervals
- ✅ **Lifecycle Integration**: Automatic flush on background via ProcessLifecycleOwner
- ✅ **Context Collection**: Device, App, OS, Screen, Network context providers
- ✅ **Thread Safety**: Atomic operations, mutex protection, and proper synchronization
- ✅ **Comprehensive Testing**: 668+ unit tests covering edge cases and concurrency
- ✅ **Modern Tooling**: Gradle 8.11, Kotlin 2.2.21 (K2), AGP 8.7.2, Java 17

## Overview

The MetaRouter Android SDK provides a robust, privacy-conscious analytics solution for Android applications. Built with Kotlin and modern Android architecture components.

## Features

- **Type-safe API**: Compile-time safety with Kotlin's type system and sealed classes
- **Automatic batching**: Efficient event transmission with configurable flush intervals
- **Circuit breaker**: Network resilience with exponential backoff and failure thresholds
- **Persistent identity**: User identity survives app restarts via SharedPreferences
- **Lifecycle-aware**: Automatically flushes events on app background
- **Proxy pattern**: Queue events before initialization, replay when ready
- **Testable**: Dependency injection throughout for easy mocking
- **Debug mode**: Comprehensive logging with PII redaction for troubleshooting

## Project Structure

```
metarouter-sdk/
├── src/main/java/com/metarouter/analytics/
│   ├── MetaRouter.kt                 # Singleton facade (entry point)
│   ├── MetaRouterAnalyticsClient.kt  # Core client implementation
│   ├── AnalyticsInterface.kt         # Public API contract
│   ├── AnalyticsProxy.kt             # Pre-init call queuing with replay
│   ├── AnalyticsExtensions.kt        # Kotlin-idiomatic varargs extensions
│   ├── InitOptions.kt                # Configuration options with validation
│   ├── context/
│   │   └── DeviceContextProvider.kt  # Device, app, OS, network context
│   ├── enrichment/
│   │   └── EventEnrichmentService.kt # Event enrichment pipeline
│   ├── identity/
│   │   └── IdentityManager.kt        # Identity management
│   ├── lifecycle/
│   │   └── AppLifecycleObserver.kt   # ProcessLifecycleOwner integration
│   ├── network/
│   │   ├── NetworkClient.kt          # OkHttp wrapper
│   │   └── CircuitBreaker.kt         # Failure handling with backoff
│   ├── queue/
│   │   └── EventQueue.kt             # Thread-safe event queue
│   ├── dispatcher/
│   │   └── Dispatcher.kt             # Batch processing and flush logic
│   ├── storage/
│   │   └── IdentityStorage.kt        # SharedPreferences wrapper
│   ├── types/
│   │   ├── EventType.kt              # Event type enum
│   │   ├── LifecycleState.kt         # SDK lifecycle states
│   │   ├── Event.kt                  # Event data models
│   │   ├── EventContext.kt           # Context data models
│   │   └── CodableValue.kt           # Type-safe JSON values
│   └── utils/
│       ├── Logger.kt                 # Logging with PII redaction
│       └── MessageIdGenerator.kt     # Unique ID generation
└── src/test/java/                    # Unit tests (668+ tests)
```

## Requirements

- **Minimum SDK**: 21 (Android 5.0 Lollipop) - covers 99%+ of devices
- **Target SDK**: 35 (Android 15)
- **Kotlin**: 2.2.21 (K2 compiler)
- **Java**: 17
- **Gradle**: 8.11

## Dependencies

### Production
- `kotlinx-serialization-json:1.9.0` - JSON serialization
- `kotlinx-coroutines-android:1.9.0` - Async operations
- `androidx.lifecycle:lifecycle-process:2.8.7` - App lifecycle observer
- `okhttp:4.12.0` - HTTP client

### Testing
- `junit:4.13.2` - Unit testing framework
- `robolectric:4.13` - Android unit testing
- `kotlinx-coroutines-test:1.9.0` - Coroutine testing utilities
- `androidx.test:core:1.6.1` - AndroidX test utilities
- `mockk:1.13.13` - Kotlin mocking library

### Optional
- `play-services-ads-identifier:18.1.0` - Google Advertising ID (if needed)

## Build Artifacts

The SDK builds to an **Android Archive (AAR)** file:

```
metarouter-sdk-release.aar (92KB)
```

**AAR Contents:**
- `classes.jar` - Compiled Kotlin bytecode
- `AndroidManifest.xml` - SDK manifest (minSdk 21)
- `proguard.txt` - ProGuard/R8 rules for consumers
- `META-INF/` - Build metadata

The AAR is significantly smaller than iOS frameworks (~200-500KB) due to bytecode efficiency.

## Installation (Coming Soon)

### Maven Central
```gradle
dependencies {
    implementation("com.metarouter:android-sdk:1.0.0")
}
```

### Local AAR
```gradle
dependencies {
    implementation(files("libs/metarouter-sdk-release.aar"))
}
```

## Usage (Preview)

```kotlin
// Initialize the SDK (once, typically in Application.onCreate())
val analytics = MetaRouter.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-host.com",
        debug = BuildConfig.DEBUG,
        flushIntervalSeconds = 10,  // Optional, default: 10
        maxQueueEvents = 2000        // Optional, default: 2000
    )
)

// Track events - idiomatic Kotlin varargs style (recommended)
analytics.track("Button Clicked",
    "button_name" to "Sign Up",
    "screen" to "Landing"
)

// Or use explicit map style for dynamic properties
val properties = mutableMapOf<String, Any?>(
    "item" to "Premium Plan",
    "price" to 29.99
)
if (user.isPremium) {
    properties["discount"] = 0.20
}
analytics.track("Purchase", properties)

// Identify users - varargs style
analytics.identify("user-123",
    "name" to "Alice",
    "email" to "alice@example.com",
    "plan" to "premium"
)

// Track screen views - varargs style
analytics.screen("Home Screen",
    "referrer" to "notification"
)

// Group users - varargs style
analytics.group("company-456",
    "name" to "Acme Corp",
    "plan" to "enterprise"
)

// Events with no properties - clean syntax
analytics.track("App Opened")

// Manually flush events
lifecycleScope.launch {
    analytics.flush()
}

// Reset on logout
lifecycleScope.launch {
    analytics.reset()
}

// Enable debug logging
analytics.enableDebugLogging()

// Get debug info
lifecycleScope.launch {
    val info = analytics.getDebugInfo()
    Log.d("MetaRouter", "Queue size: ${info["queueLength"]}")
}
```

### Viewing Logs

Filter logcat by the "MetaRouter" tag:

```bash
adb logcat -s MetaRouter
```

Or in Android Studio: Filter by "MetaRouter"

## Testing

Run unit tests:

```bash
./gradlew :metarouter-sdk:test
```

Run specific test suite:

```bash
./gradlew :metarouter-sdk:test --tests "*LoggerTest*"
```

Build the SDK:

```bash
./gradlew :metarouter-sdk:build
```

Clean and rebuild:

```bash
./gradlew clean build
```

## Specification Compliance

This SDK is built to strictly follow the [MetaRouter SDK Standardization Specification v1.3.0](Untitled-1.json), ensuring consistent behavior across iOS, Android, and React Native platforms.

### Specification Compliance ✅
- ✅ Initialization validation with spec-compliant error messages
- ✅ Event types and payload structure (Track, Identify, Group, Screen, Page, Alias)
- ✅ Type-safe property handling (CodableValue sealed class)
- ✅ Lifecycle states (Idle, Initializing, Ready, Resetting, Disabled)
- ✅ Logger with PII redaction and write key masking
- ✅ Message ID format: `{timestamp-ms}-{uuid}`
- ✅ Identity management and persistence
- ✅ Queue management with overflow behavior (FIFO, drop oldest)
- ✅ Network resilience with circuit breaker and exponential backoff
- ✅ Automatic lifecycle handling (flush on background)

## Development Setup

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 21-35

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/metarouterio/android-sdk.git
   cd android-sdk
   ```

2. Build the SDK:
   ```bash
   ./gradlew :metarouter-sdk:build
   ```

3. Run tests:
   ```bash
   ./gradlew :metarouter-sdk:test
   ```

4. Output will be in:
   ```
   metarouter-sdk/build/outputs/aar/metarouter-sdk-release.aar
   ```

## License

MIT

## Support

For issues, questions, or contributions, please visit [GitHub Issues](https://github.com/metarouterio/android-sdk/issues).

