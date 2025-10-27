# MetaRouter Android SDK

Native Kotlin/Android SDK for MetaRouter analytics platform.

## 🚧 Work in Progress

This SDK is currently under active development. This is **PR #1: Foundation & Type System**.

### Current Status

- ✅ Project structure and Gradle configuration
- ✅ Core type system (EventType, LifecycleState)
- ✅ Event data models (Event, EventContext)
- ✅ CodableValue for type-safe properties
- ✅ InitOptions with validation
- ✅ AnalyticsInterface (public API contract)
- ✅ Utility classes (Logger with android.util.Log, MessageIdGenerator)
- ✅ Comprehensive unit tests (73 tests, 100% passing)
- ✅ Modern tooling (Gradle 8.11, Kotlin 2.2.21, AGP 8.7.2)
- ⏳ Identity management (PR #2)
- ⏳ Context collection (PR #3)
- ⏳ Event enrichment & queueing (PR #4)
- ⏳ Network layer & circuit breaker (PR #5)
- ⏳ Dispatcher & flush logic (PR #6)
- ⏳ Client initialization & proxy pattern (PR #7)
- ⏳ Lifecycle integration (PR #8)
- ⏳ Advertising ID (GAID) support (PR #9)
- ⏳ Debug utilities & polish (PR #10)

## Overview

The MetaRouter Android SDK provides a robust, privacy-conscious analytics solution for Android applications. Built with Kotlin and modern Android architecture components.

## Features (Planned)

- 🎯 **Type-safe API**: Compile-time safety with Kotlin's type system
- 🔄 **Automatic batching**: Efficient event transmission with configurable intervals
- 🛡️ **Circuit breaker**: Network resilience with exponential backoff
- 💾 **Persistent identity**: User identity survives app restarts
- 🔐 **Privacy-first**: GDPR/CCPA compliant with opt-out support
- 📱 **Lifecycle-aware**: Automatically handles app foreground/background
- 🧪 **Testable**: Dependency injection throughout
- 🔍 **Observable**: Debug mode for troubleshooting with logcat integration

## Project Structure

```
metarouter-sdk/
├── src/main/java/com/metarouter/analytics/
│   ├── AnalyticsInterface.kt         # Public API contract
│   ├── InitOptions.kt                # Configuration options with validation
│   ├── types/
│   │   ├── EventType.kt             # Event type enum (Track, Identify, etc.)
│   │   ├── LifecycleState.kt        # SDK lifecycle states
│   │   ├── Event.kt                 # Event data models
│   │   ├── EventContext.kt          # Context data models
│   │   └── CodableValue.kt          # Type-safe JSON values
│   └── utils/
│       ├── Logger.kt                 # Thread-safe logging with android.util.Log
│       └── MessageIdGenerator.kt     # Unique ID generation
└── src/test/java/                    # Unit tests (73 tests, 100% passing)
```

## Requirements

- **Minimum SDK**: 21 (Android 5.0 Lollipop) - covers 99%+ of devices
- **Target SDK**: 35 (Android 15)
- **Kotlin**: 2.2.21 (K2 compiler)
- **Java**: 17
- **Gradle**: 8.11

## Dependencies

### Production (PR #1)
- `kotlinx-serialization-json:1.9.0` - JSON serialization for CodableValue

### Testing (PR #1)
- `junit:4.13.2` - Unit testing framework
- `robolectric:4.13` - Android unit testing

### Future Dependencies (Coming in Later PRs)
- `kotlinx-coroutines-android:1.9.0` - Async operations (PR #4+)
- `androidx.lifecycle:lifecycle-process:2.8.7` - App lifecycle (PR #8)
- `okhttp:4.12.0` - HTTP client (PR #5)
- `play-services-ads-identifier:18.1.0` - Google Advertising ID (PR #9)

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

// Track events
analytics.track("Button Clicked", mapOf(
    "button_name" to "Sign Up",
    "screen" to "Landing"
))

// Identify users
analytics.identify("user-123", mapOf(
    "name" to "Alice",
    "email" to "alice@example.com",
    "plan" to "premium"
))

// Track screen views
analytics.screen("Home Screen", mapOf(
    "referrer" to "notification"
))

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

### PR #1 Compliance ✅
- ✅ Initialization validation with spec-compliant error messages
- ✅ Event types and payload structure (Track, Identify, Group, Screen, Page, Alias)
- ✅ Type-safe property handling (CodableValue sealed class)
- ✅ Lifecycle states (Idle, Initializing, Ready, Resetting, Disabled)
- ✅ Logger with PII redaction and write key masking
- ✅ Message ID format: `{timestamp-ms}-{uuid}`

### Future PRs ⏳
- ⏳ Identity management and persistence
- ⏳ Queue management with overflow behavior (FIFO, drop oldest)
- ⏳ Network resilience with circuit breaker and exponential backoff
- ⏳ Advertising ID handling for GAID with user consent checks
- ⏳ GDPR/CCPA compliance features

## Contributing

This is the first PR in a series. See the project plan below for the complete roadmap.

### PR Breakdown

1. **PR #1: Foundation & Type System** ← You are here
   - ✅ 73 unit tests passing
   - ✅ 92KB release AAR
   - ✅ Modern tooling (Gradle 8.11, Kotlin 2.2.21, Java 17)

2. **PR #2: Identity Management**
   - IdentityStorage (SharedPreferences wrapper)
   - IdentityManager (thread-safe with Mutex)
   - Anonymous ID generation and persistence

3. **PR #3: Context Collection**
   - Device, App, OS, Screen, Network context providers
   - Context caching strategy

4. **PR #4: Event Enrichment & Queueing**
   - EventEnrichmentService
   - EventQueue with overflow handling
   - Wire up all API methods

5. **PR #5: Network Layer & Circuit Breaker**
   - NetworkClient (OkHttp wrapper)
   - CircuitBreaker with exponential backoff
   - Status code handling

6. **PR #6: Dispatcher & Flush Logic**
   - Dispatcher with coroutines
   - Batch processing and periodic flush
   - Retry logic

7. **PR #7: Client Initialization & Proxy Pattern**
   - AnalyticsClient implementation
   - AnalyticsProxy for early calls
   - MetaRouter singleton facade

8. **PR #8: Lifecycle Integration**
   - AppLifecycleObserver (ProcessLifecycleOwner)
   - Flush on background
   - Pause on background

9. **PR #9: Advertising ID (GAID)**
   - setAdvertisingId/clearAdvertisingId
   - User consent handling
   - Privacy compliance

10. **PR #10: Debug Utilities & Polish**
    - Enhanced debug mode
    - ProGuard rules
    - Sample app
    - Documentation

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

## Version History
