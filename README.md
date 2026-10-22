# MetaRouter Android SDK

[![CI](https://github.com/metarouterio/android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/metarouterio/android-sdk/actions/workflows/ci.yml)
[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

A lightweight Android analytics SDK that transmits events to your MetaRouter cluster.

## Table of Contents

- [Installation](#installation)
- [Usage](#usage)
  - [Basic Setup](#basic-setup)
  - [Kotlin Varargs Style](#kotlin-varargs-style)
  - [Direct Usage](#direct-usage)
- [API Reference](#api-reference)
- [Features](#features)
- [Compatibility](#-compatibility)
- [Debugging](#debugging)
- [Identity Persistence](#identity-persistence)
- [Lifecycle Events](#lifecycle-events)
- [Session Tracking](#session-tracking)
- [Consent](#consent)
- [Provider Context](#provider-context)
- [WebView Bridge](#webview-bridge)
- [Advertising ID (GAID)](#advertising-id-gaid)
- [Using the alias() Method](#using-the-alias-method)
- [License](#license)

## Installation

### JitPack

Add the JitPack repository to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency in your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.metarouterio:android-sdk:1.5.0")
}
```


## Usage

### Basic Setup

```kotlin
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.InitOptions

// Initialize the SDK (once, typically in Application.onCreate())
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
        debug = true, // Optional: enable debug mode
        flushIntervalSeconds = 30, // Optional: flush events every 30 seconds
    )
)
```

### Kotlin Varargs Style

The SDK provides idiomatic Kotlin extensions using `Pair` varargs for concise property passing:

```kotlin
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.InitOptions

val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
    )
)

// Track events
analytics.track("Button Clicked",
    "button_name" to "Submit",
    "screen" to "Home"
)

// Identify users
analytics.identify("user123",
    "name" to "John Doe",
    "email" to "john@example.com"
)

// Track screen views
analytics.screen("Home Screen",
    "category" to "navigation"
)

// Group users
analytics.group("company123",
    "name" to "Acme Corp",
    "industry" to "technology"
)
```

### Direct Usage

```kotlin
import com.metarouter.analytics.MetaRouter
import com.metarouter.analytics.InitOptions

// Initialize the client — returns a proxy you can use immediately.
// Events are queued in-memory and replayed when the client is ready.
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
    )
)

// Track events
analytics.track("User Action", mapOf(
    "action" to "button_click",
    "screen" to "home",
))

// Identify users
analytics.identify("user123", mapOf(
    "name" to "John Doe",
    "email" to "john@example.com",
))

// Track screen views
analytics.screen("Home Screen", mapOf(
    "category" to "navigation",
))

// Track page views
analytics.page("Home Page", mapOf(
    "url" to "/home",
    "referrer" to "/landing",
))

// Group users
analytics.group("company123", mapOf(
    "name" to "Acme Corp",
    "industry" to "technology",
))

// Flush events immediately
lifecycleScope.launch {
    analytics.flush()
}

// Reset analytics (useful for testing or logout)
lifecycleScope.launch {
    analytics.reset()
}
```

### Suspending Initialization

If you need to wait for initialization to complete before proceeding, use the suspending variant:

```kotlin
lifecycleScope.launch {
    val analytics = MetaRouter.Analytics.initializeAndWait(
        context = applicationContext,
        options = InitOptions(
            writeKey = "your-write-key",
            ingestionHost = "https://your-ingestion-endpoint.com",
        )
    )
    // Client is fully initialized here
    analytics.track("App Started")
}
```

## API Reference

### MetaRouter.Analytics.initialize(context, options)

Initializes the analytics client and returns a **live proxy** to the client instance.

`initialize()` returns immediately. You **do not** need to wait before calling analytics methods.

Calls to `track`, `identify`, etc. are **buffered in-memory** by the proxy and replayed **in order** once the client is fully initialized.

**Options (InitOptions):**

- `writeKey` (String, required): Your write key
- `ingestionHost` (String, required): Your MetaRouter ingestor host
- `debug` (Boolean, optional): Enable debug mode
- `flushIntervalSeconds` (Int, optional): Interval in seconds to flush events (default: 10)
- `maxQueueEvents` (Int, optional): Max events stored in memory (default: 2000)
- `maxDiskEvents` (Int, optional): Max events persisted to disk during extended offline periods (default: 10000). Set to `0` to disable disk persistence entirely — the queue then operates as a pure in-memory ring buffer (oldest event dropped at capacity). Negative values clamp to 0 with a warning.
- `onConfigError` ((ConfigError) -> Unit, optional): Fired synchronously by `initialize` when the options were constructed from invalid config — the programmatic complement to the error log (see [Invalid configuration](#invalid-configuration) below)

**Invalid configuration:**

Constructing `InitOptions` never throws. Invalid input (empty write key, non-http(s) or host-less ingestion host) is recorded on the options, and `MetaRouter.Analytics.initialize` reads the verdict:

- **Debuggable builds** fail fast: `initialize` throws `IllegalArgumentException` so typos surface immediately during development.
- **Release builds** never crash the host app: `initialize` logs an always-on error, fires `onConfigError` (if set), and returns the proxy with **no client created** — the SDK is inert for the session, mirroring the `401/403/404` graceful-disable. A later `initialize` with valid options recovers the session.
- Numeric bounds (`flushIntervalSeconds`, `maxQueueEvents`, `maxDiskEvents`) clamp with a warning instead of failing.
- `getDebugInfo()["configError"]` carries the error description for a refused session, so misconfiguration is diagnosable from the same API as every other SDK state.

**Proxy behavior (quick notes):**

- The proxy buffer is **in-memory only** — calls made before the client is ready are lost if the process exits before initialization completes. Once the client is ready, events are persisted to disk and survive process kills (see [Delivery & Backoff](#delivery--backoff-how-events-flow-under-failures)).
- Ordering is preserved relative to other buffered calls; normal FIFO + batching applies after ready.
- On fatal config errors (`401/403/404`), the client enters **disabled** state and drops subsequent calls.
- `sentAt` is stamped when the batch is prepared for transmission (just before network send). If you need the original occurrence time, pass your own `timestamp` on each event.

### MetaRouter.Analytics.shared

The analytics client, available at any time — before `initialize`, during initialization, and after `reset()`. It is the same proxy `initialize` returns, and it never throws. Calls made before `initialize` follow the proxy rules above; consent calls act immediately (see [Consent](#consent)). `MetaRouter.Analytics.client()` keeps working and still throws until `initialize` has been called.

```kotlin
MetaRouter.Analytics.shared.track("Button Tapped")
```

### Analytics Interface

The analytics client provides the following methods:

- `track(event: String, properties: Map<String, Any?>?)`: Track custom events
- `identify(userId: String, traits: Map<String, Any?>?)`: Identify users
- `group(groupId: String, traits: Map<String, Any?>?)`: Group users
- `screen(name: String, properties: Map<String, Any?>?)`: Track screen views
- `page(name: String, properties: Map<String, Any?>?)`: Track page views
- `alias(newUserId: String)`: Connect anonymous users to known user IDs. See [Using the alias() Method](#using-the-alias-method) for details
- `setAdvertisingId(advertisingId: String)`: Set the Google Advertising ID (GAID) for ad tracking. See [Advertising ID](#advertising-id-gaid) section for usage and compliance requirements
- `clearAdvertisingId()`: Clear the advertising identifier from storage and context. Useful for GDPR/CCPA compliance when users opt out of ad tracking
- `getAnonymousId(): String` (suspend): Retrieve the current anonymous ID. Suspends until the SDK is initialized and ready, then returns immediately on subsequent calls.
- `getSessionId(): String?` (suspend): The current analytics session id, or `null` before the first event of the process. Never suspends for initialization and never starts or extends a session. See [Session Tracking](#session-tracking)
- `setTracing(enabled: Boolean)`: Enable or disable tracing headers on API requests. When enabled, includes a `Trace: true` header for debugging request flows
- `recordOpenedUrl(uri: Uri, sourceApplication: String? = null)`: Buffer a deep-link URL for the next `Application Opened` event. See [Lifecycle Events](#lifecycle-events) for wiring details
- `attachWebView(webView: WebView, allowedOrigins: List<String>)`: Capture track/page events emitted by pages inside a host-owned WebView. See [WebView Bridge](#webview-bridge) for wiring details
- `flush()`: Flush events immediately (suspending)
- `reset()`: Reset analytics state: clears identity (user, anonymous and group ids, advertising ID) and the event queue (suspending). Consent is **not** cleared; see `clearConsent()`. Also available as fire-and-forget via `MetaRouter.Analytics.reset()`
- `enableDebugLogging()`: Enable debug logging
- `getDebugInfo()`: Get current debug information (suspending)
- `registerConsentCategories(categories: List<ConsentCategory>)`: Register the consent categories the pipeline gates on and each one's default posture. Call on `MetaRouter.Analytics.shared` before `initialize`. See [Consent](#consent)
- `setConsent(consent: Consent): Boolean`: Push the user's consent into the SDK. Reconciled against the registered categories, persisted across launches, stamped on every subsequent event as `context.consent`. Usable before `initialize`. Returns `false` when the snapshot was ignored as stale
- `getConsent(): Consent`: The current reconciled consent. Never null: the implicit default (`explicit: false`, registered defaults) until the first `setConsent`
- `clearConsent()`: Consent withdrawal — back to the implicit default, persisted state (timestamp history included) wiped. `reset()` does **not** do this
- `subscribeToConsent(handler: (Consent) -> Unit): ConsentSubscription`: Observe consent changes on the main thread; the first delivery is the current value, on the next main-looper turn. Keep the returned subscription and call `close()` to stop; it implements `Closeable`
- `setProviderContext(provider: String, key: String, value: Any?): Boolean`: Stamp a value at `context.providers.<provider>.<key>` on every event. Persisted, survives `reset()`, usable before `initialize`. `null` removes the key. Returns `false` when the write is rejected. See [Provider Context](#provider-context)
- `getProviderContext()`, `clearProviderContext(provider: String)`, `clearAllProviderContext()`: Read, remove one provider's block, remove everything

### Kotlin Extension Methods

Idiomatic Kotlin varargs overloads for all event methods:

```kotlin
analytics.track("Event", "key1" to value1, "key2" to value2)
analytics.identify("user-123", "name" to "Alice", "email" to "alice@example.com")
analytics.group("company-456", "plan" to "enterprise")
analytics.screen("Home", "referrer" to "notification")
analytics.page("Landing", "url" to "/home")
```


## ✅ Compatibility

| Component              | Supported Versions   |
| ---------------------- | -------------------- |
| Android Min SDK        | >= API 23 (6.0)      |
| Android Target SDK     | API 35 (15)          |
| Kotlin                 | >= 2.0               |
| Java                   | >= 17                |
| Gradle                 | >= 8.0               |

## Debugging

If you're not seeing API calls being made, here are some steps to troubleshoot:

### 1. Enable Debug Logging

```kotlin
// Initialize with debug enabled
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
        debug = true, // This enables detailed logging
    )
)

// Or enable debug logging after initialization
analytics.enableDebugLogging()
```

### 2. Check Debug Information

```kotlin
// Get current state information
lifecycleScope.launch {
    val debugInfo = analytics.getDebugInfo()
    Log.d("MetaRouter", "Analytics debug info: $debugInfo")
}
```

### 3. Force Flush Events

```kotlin
// Manually flush events to see if they're being sent
lifecycleScope.launch {
    analytics.flush()
}
```

### 4. View Logs

Filter logcat by the `MetaRouter` tag:

```bash
adb logcat -s MetaRouter
```

Or in Android Studio: Filter by "MetaRouter"

### 5. Common Issues

- **Network Permissions**: Ensure your app has `android.permission.INTERNET` in your manifest
- **SharedPreferences**: The SDK uses SharedPreferences for identity persistence (anonymousId, userId, groupId, advertisingId)
- **Endpoint URL**: Verify your ingestion endpoint is correct and accessible
- **Write Key**: Ensure your write key is valid

### Delivery & Backoff (How events flow under failures)

Queue capacity: The SDK keeps up to 2,000 events in memory (configurable via `maxQueueEvents` in `InitOptions`) with an additional 5 MB byte cap. When either limit is reached, the oldest events are dropped first (drop-oldest).

Disk persistence: The SDK writes a snapshot of the in-memory queue to disk when the app goes to background, when a flush threshold is reached (500 events or 2 MB), and on low-memory callbacks. On next launch, events are rehydrated from disk — events older than 7 days are dropped during rehydration. The snapshot is stored in `noBackupFilesDir` and is deleted after successful rehydration or on `reset()`. Disk capacity is bounded by `maxDiskEvents` (default 10,000) — when the cap is hit, the oldest persisted events are dropped first.

Opting out of disk persistence: Set `maxDiskEvents = 0` to disable the disk store entirely. In this mode the SDK behaves as a pure in-memory ring buffer — `maxQueueEvents` (and the 5 MB byte cap) still bound the queue, and the oldest event is dropped when full. Background/low-memory crash-safety flushes become no-ops, so events in memory will not survive a process kill. Use this when local persistence is unwanted (privacy constraints, ephemeral environments, tests).

This SDK uses a circuit breaker around network I/O. It keeps ordering stable, avoids tight retry loops, and backs off cleanly when your cluster is unhealthy or throttling.

Queueing during backoff: While the breaker is OPEN, new events are accepted and appended to the in-memory queue; nothing is sent until the cooldown elapses.

Ordering (FIFO): If a batch fails with a retryable error, that batch is requeued at the front (original order preserved). New events go to the tail. After cooldown, we try again; on success we continue draining in order.

Half-open probe: After cooldown, one probe is allowed.
Success → breaker CLOSED (keep flushing).
Failure → breaker OPEN again with longer cooldown.

sentAt semantics: sentAt is stamped when the batch is prepared for transmission. If the client is backing off, the actual transmit may be later; sentAt reflects when the event entered the queue.

| Status / Failure                    | Action                                                               | Breaker | Queue effect                   |
| ----------------------------------- | -------------------------------------------------------------------- | ------- | ------------------------------ |
| `2xx`                               | Success                                                              | close   | Batch removed                  |
| `5xx`                               | Retry: requeue **front**, schedule after cooldown                    | open↑   | Requeued (front)               |
| `408` (timeout)                     | Retry: requeue **front**, schedule after cooldown                    | open↑   | Requeued (front)               |
| `429` (throttle)                    | Retry: requeue **front**, wait = `max(Retry-After, breaker, 1000ms)` | open↑   | Requeued (front)               |
| `413` (payload too large)           | Halve `maxBatchSize`; requeue and retry; if already `1`, **drop**    | close   | Requeued or dropped (`size=1`) |
| `400`, `422`, other non-fatal `4xx` | **Drop** bad batch, continue                                         | close   | Dropped                        |
| `401`, `403`, `404`                 | **Disable** client (stop timers), clear queue                        | close   | Cleared                        |
| Network error / Abort / Timeout     | Retry: requeue **front**, schedule after cooldown                    | open↑   | Requeued (front)               |
| Reset during flush                  | Do **not** requeue in-flight chunk; **drop** it                      | —       | Dropped                        |

**Defaults:** `failureThreshold=3`, `cooldownMs=10s`, `maxCooldownMs=120s`, `jitter=±20%`, `halfOpenMaxConcurrent=1`.

**Identifiers:**

- `anonymousId` is a stable, persisted UUID for the device/user before identify; it does **not** include timestamps.
- `messageId` is generated as `<epochMillis>-<uuid>` (e.g., `1734691572843-6f0c7e85-...`) to aid debugging.

## Identity Persistence

The MetaRouter Android SDK automatically manages and persists user identifiers across app sessions using Android's SharedPreferences. This ensures consistent user tracking even after app restarts.

### The Four Identity Fields

#### 1. userId (Common User ID)

The `userId` is set when you identify a user and represents their unique identifier in your system (e.g., database ID, email, employee ID).

**How to set:**

```kotlin
analytics.identify("user123",
    "name" to "John Doe",
    "email" to "john@example.com",
    "role" to "Sales Associate"
)
```

**Behavior:**

- Persisted to SharedPreferences (key: `metarouter:user_id`)
- Automatically loaded on app restart
- Automatically included in **all** subsequent events (`track`, `page`, `screen`, `group`)
- Remains set until `reset()` is called or app is uninstalled

**Example flow:**

```kotlin
// Day 1: User logs in
analytics.identify("employeeID", "name" to "Jane")
analytics.track("Product Viewed", "sku" to "ABC123")
// Event includes: userId: "employeeID"

// App restarts...

// Day 2: User opens app
analytics.track("App Opened")
// Event STILL includes: userId: "employeeID" (auto-loaded from storage)
```

#### 2. anonymousId

The `anonymousId` is a unique identifier automatically generated for each device/installation before a user is identified.

**How it's set:**

- **Automatically** generated as a UUID v4 on first SDK initialization
- No manual action required

**Behavior:**

- Persisted to SharedPreferences (key: `metarouter:anonymous_id`)
- Automatically loaded on app restart
- Automatically included in **all** events
- Remains stable across app sessions until `reset()` is called
- Cleared on `reset()` and a **new** UUID is generated on next `initialize()`

**Reading the current value:**

```kotlin
// analytics is the AnalyticsInterface returned from initialize() / initializeAndWait()
val anonId = analytics.getAnonymousId()
// Suspends until the SDK is ready, then returns the anonymous ID
```

**Use case:**
Track user behavior before they log in or create an account, then connect pre-login and post-login activity using the `alias()` method.

#### 3. groupId

The `groupId` associates a user with an organization, team, account, or other group entity.

**How to set:**

```kotlin
analytics.group("company123",
    "name" to "Acme Corp",
    "plan" to "Enterprise",
    "industry" to "Technology"
)
```

**Behavior:**

- Persisted to SharedPreferences (key: `metarouter:group_id`)
- Automatically loaded on app restart
- Automatically included in **all** subsequent events after being set
- Remains set until `reset()` is called

**Example use case:**

```kotlin
// User logs into their company account
analytics.identify("user123", "name" to "Jane")
analytics.group("acme-corp", "name" to "Acme Corp")

// All future events include both userId and groupId
analytics.track("Report Generated")
// Event includes: userId: "user123", groupId: "acme-corp"
```

#### 4. advertisingId (Optional)

The `advertisingId` is used for ad tracking and attribution (GAID on Android). See the [Advertising ID](#advertising-id-gaid) section below for detailed usage and compliance requirements.

### Persistence Summary

| Field             | Set By                   | Storage Key                 | Auto-Attached        | Cleared By                                |
| ----------------- | ------------------------ | --------------------------- | -------------------- | ----------------------------------------- |
| **userId**        | `identify(userId)`       | `metarouter:user_id`        | All events           | `reset()`                                 |
| **anonymousId**   | Auto-generated (UUID v4) | `metarouter:anonymous_id`   | All events           | `reset()` (new ID generated on next init) |
| **groupId**       | `group(groupId)`         | `metarouter:group_id`       | All events after set | `reset()`                                 |
| **advertisingId** | `setAdvertisingId(id)`   | `metarouter:advertising_id` | Event context        | `clearAdvertisingId()`, `reset()`         |
| **consent**       | `setConsent(consent)`    | `metarouter:consent:state`  | All events (`context.consent`) | `clearConsent()` only (survives `reset()`) |
| **provider context** | `setProviderContext(…)` | `metarouter:providers:context` | All events (`context.providers`) | `clearProviderContext`, `clearAllProviderContext` (survives `reset()`) |

### Event Enrichment Flow

Every event you send (track, page, screen, group) is automatically enriched with persisted identity information:

```kotlin
// You call:
analytics.track("Button Clicked", "buttonName" to "Submit")

// SDK automatically sends:
{
  "type": "track",
  "event": "Button Clicked",
  "properties": { "buttonName": "Submit" },
  "userId": "employeeID",        // ← Auto-added from storage
  "anonymousId": "a1b2c3d4-...", // ← Auto-added from storage
  "groupId": "company123",       // ← Auto-added from storage (if set)
  "timestamp": "2025-10-23T...",
  "context": {
    "device": {
      "advertisingId": "...",    // ← Auto-added from storage (if set)
      "manufacturer": "Samsung",
      "model": "Galaxy S24",
      "type": "android"
    },
    "os": { "name": "Android", "version": "15" },
    "app": { "name": "MyApp", "version": "1.0.0", "build": "42" },
    "screen": { "width": 411, "height": 891, "density": 2.63 },
    "network": { "wifi": true },
    "library": { "name": "metarouter-android-sdk", "version": "1.5.0" },
    "locale": "en-US",
    "timezone": "America/New_York"
  }
}
```

### Resetting Identity

Call `reset()` to clear **all** identity data, typically when a user logs out:

```kotlin
lifecycleScope.launch {
    analytics.reset()
}

// Or fire-and-forget
MetaRouter.Analytics.reset()
```

**What `reset()` does:**

- Clears `userId`, `anonymousId`, `groupId`, and `advertisingId` from memory
- Removes all identity fields from SharedPreferences
- Stops background flush loops
- Clears event queue and deletes the disk snapshot
- Next `initialize()` will generate a **new** `anonymousId`

**Common logout flow:**

```kotlin
// User logs out
lifecycleScope.launch {
    analytics.reset()
}

// User is now tracked with a new anonymousId (auto-generated on next event)
// No userId or groupId until they log in again
```

### Best Practices

1. **On Login:** Call `identify()` immediately after successful authentication
2. **On Logout:** Call `reset()` to clear user identity
3. **Cross-Session Tracking:** The SDK handles this automatically — no action needed
4. **Group Associations:** Set `groupId` after determining the user's organization/team
5. **Pre-Login Tracking:** Events are tracked with `anonymousId` before login
6. **Connecting Sessions:** Use `alias()` to connect pre-login and post-login activity

### Example: Complete User Journey

```kotlin
// App starts - SDK initializes
val analytics = MetaRouter.Analytics.initialize(context, options)
// anonymousId: "abc-123" (auto-generated and persisted)

// User browses before login
analytics.track("Product Viewed", "sku" to "XYZ")
// Includes: anonymousId: "abc-123"

// User logs in
analytics.identify("user456", "name" to "John", "email" to "john@example.com")
// userId: "user456" is now persisted

// User performs actions
analytics.track("Added to Cart", "sku" to "XYZ")
// Includes: userId: "user456", anonymousId: "abc-123"

// App closes and reopens...

// SDK auto-loads userId from storage
analytics.track("App Reopened")
// STILL includes: userId: "user456", anonymousId: "abc-123"

// User logs out
lifecycleScope.launch {
    analytics.reset()
}
// All IDs cleared, new anonymousId will be generated on next init
```

### Storage Location

All identity data is stored in **Android SharedPreferences** (`com.metarouter.analytics` namespace), which provides:

- Persistent storage across app sessions
- Thread-safe access with built-in synchronization
- Cleared only on app uninstall or explicit `reset()` call

## Lifecycle Events

Opt-in automatic emission of four canonical application-lifecycle events: `Application Installed`, `Application Updated`, `Application Opened`, and `Application Backgrounded`. These anchor attribution, retention, and session reporting on top of the standard event pipeline.

### Enabling

Lifecycle events are **opt-in**. Set `trackLifecycleEvents = true` on `InitOptions` to enable:

```kotlin
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
        trackLifecycleEvents = true,
    )
)
```

When the flag is `false` (the default), the SDK never emits these events and `recordOpenedUrl(...)` is a no-op that logs a warning. Existing customers upgrading the SDK do **not** start emitting lifecycle events without explicitly setting the flag.

### Events

| Event | When it fires | Properties |
|---|---|---|
| `Application Installed` | First launch after a truly fresh install (no prior identity, no prior lifecycle storage) | `version`, `build` |
| `Application Updated` | First launch after a `(version, build)` change OR first launch after upgrading from a pre-lifecycle SDK build (existing identity, no lifecycle storage) | `version`, `build`, `previous_version`, `previous_build` (set to `"unknown"` for the SDK-upgrade case) |
| `Application Opened` | Cold launch (foreground at SDK init) and on every `ProcessLifecycleOwner.ON_START` resume | `version`, `build`, `from_background` (false on cold launch, true on resume), optional `url` and `referring_application` from `recordOpenedUrl(...)` |
| `Application Backgrounded` | `ProcessLifecycleOwner.ON_STOP`. Emitted **before** the dispatcher's flush-to-disk so the event ships in the same drain | (none) |

### Cold-launch sequencing

On cold launch, install/update events fire **before** `Application Opened` so attribution pipelines see the install/update before the session start.

For background-launched processes (silent push, `JobScheduler`, `WorkManager`, broadcast receiver, content provider) where `ProcessLifecycleOwner.lifecycle.currentState` is below `STARTED` at SDK init, the cold-launch `Application Opened` is suppressed. The first subsequent `ON_START` transition emits the bridge event with `from_background = false` so analytics still see one Opened per app session — just at the moment the user actually engages.

### Persistence

`(version, build)` are persisted to SharedPreferences under `com.metarouter.analytics.lifecycle`, a separate file from identity prefs. `reset()` clears identity but **not** lifecycle storage — install/update is device-scope, not user-scope. Re-launching after `reset()` with the same `(version, build)` emits only `Application Opened`.

### Deep links

The SDK does not auto-instrument deep links. Hosts forward URLs explicitly via `analytics.recordOpenedUrl(uri, sourceApplication)`. The next `Application Opened` event the SDK emits will carry `url` and (if provided) `referring_application` properties. The buffer is **one-shot** (cleared on emit) and **last-write-wins** (multiple calls before the next Opened keep only the most recent URL).

#### Activity entry point

```kotlin
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.metarouter.analytics.MetaRouter

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop / singleTask resumed via a fresh intent
        forwardDeepLink(intent)
    }

    private fun forwardDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // `Activity.referrer` is the canonical Android API for the calling app's host.
        // (Don't use `Intent.EXTRA_REFERRER` with `getStringExtra` — it's documented as
        // a `Uri`, so the String overload returns null.)
        val source = referrer?.host
        MetaRouter.Analytics.client().recordOpenedUrl(uri, source)
    }
}
```

#### App Links (verified deep links)

Wire up your manifest as usual; nothing changes for the SDK side:

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="example.com" />
    </intent-filter>
</activity>
```

### Privacy

Deep-link URLs frequently carry sensitive material — auth tokens, OTPs, magic-link secrets, query parameters with PII. The SDK forwards whatever URI you hand it and does not sanitize. Strip or redact secrets in the host before calling `recordOpenedUrl(...)`:

```kotlin
import android.net.Uri

private fun sanitize(uri: Uri): Uri {
    val sensitiveParams = setOf("token", "otp", "code", "auth", "secret")
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames
        .filterNot { it.lowercase() in sensitiveParams }
        .forEach { name ->
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
    return builder.build()
}

intent.data?.let { uri ->
    MetaRouter.Analytics.client().recordOpenedUrl(sanitize(uri), referrer?.host)
}
```

### Why no auto-instrumentation?

Several reasons:

- **No manifest mutation.** A library that rewrites your manifest's intent filters fights with build pipelines, app-bundle splits, and dynamic feature modules.
- **No `ActivityLifecycleCallbacks` proxy.** Auto-forwarding every Activity's `onCreate`/`onNewIntent` would force the SDK to interpret URLs that aren't deep links (e.g., internal navigation routed via `Intent`), and it would fight any lifecycle-callbacks instrumentation the host already runs.
- **Privacy footgun.** Auto-forwarded URLs would ship sensitive material without the host having a chance to sanitize.
- **Host control.** You already know which entry points are deep-link entry points. Forwarding from those specific Activities is a one-liner; auto-instrumentation would be lossy and surprising.

## Session Tracking

Every event carries the analytics session it belongs to, stamped at enrichment as:

```json
"context": {
  "providers": {
    "metarouter": {
      "sessionID": "1757400000000",
      "sessionCount": 3
    }
  }
}
```

This is the same path and shape the web SDK's MetaRouter session sync produces (and the iOS SDK stamps), so pipeline mappings (e.g. GA4 `session_id` / `session_number`) read one field from every platform. `sessionID` is the epoch-millisecond timestamp of the session's first activity, as a string; `sessionCount` is the lifetime session ordinal for the install.

Session stamping is **always on** — there is nothing to enable and no network cost. A session is minted lazily by the first event and ends after `sessionTimeoutMinutes` (default 30) of inactivity; the window slides, so steady activity keeps one session alive indefinitely. Any tracked, lifecycle, or webview-bridge event counts as activity. Sessions survive process restarts that happen inside the window, and survive `reset()` — a logout mid-session does not fragment the session.

### Session Started event

Optionally, the SDK emits a `Session Started` track event (no properties, matching web) each time a new session is minted:

```kotlin
val options = InitOptions(
    writeKey = "YOUR_WRITE_KEY",
    ingestionHost = "https://your-ingestion-host.com",
    sessionTimeoutMinutes = 30,   // default
    fireSessionStarted = true     // default false
)
```

`fireSessionStarted` is off by default so upgrading the SDK never changes an app's event volume. Turn it on when a downstream destination needs an explicit session-start signal (GA4's `session_start`, for example).

## Consent

The host app owns consent acquisition — a CMP SDK callback, a cookie read out of a WebView, your own preference screen. The SDK owns storage, reconciliation, observation, and putting consent on the wire. Every event carries the current consent at `context.consent`, in the same shape the web SDK emits, so the cluster enforces one set of per-integration rules for web and mobile:

```json
"context": {
  "consent": {
    "explicit": true,
    "optOut": {
      "c0001": false,
      "c0002": false,
      "c0004": true
    }
  }
}
```

- `explicit` — `true` when the user made an explicit choice, `false` for an implicit or regional default. You set it; the SDK cannot infer it.
- `optOut` — a flat map with **opt-out** semantics: `true` means blocked. Keys are lowercase and pass through from your CMP (`c0001`…, `analytics`, `advertising`, …). `setConsent` canonicalizes the keys you pass (`C0004` → `c0004`); when two keys collapse to one, opted-out wins.

Consent never gates delivery. Events send immediately and the server gates per integration, so there is no buffering, no init flag, and nothing to wait for.

### 1. Register categories — before `initialize`

Register the category set your pipeline gates on, with each category's default posture. Default is opt-out (`defaultOptOut = true`): a category is opted *in* deliberately, and the absence of a signal never opts a user in.

```kotlin
// Consent calls never wait for a client, so register first…
MetaRouter.Analytics.shared.registerConsentCategories(listOf(
    ConsentCategory("c0001", defaultOptOut = false), // strictly necessary — always on
    ConsentCategory("c0002"),                        // performance
    ConsentCategory("c0004"),                        // targeting
))

// …then initialize.
val analytics = MetaRouter.Analytics.initialize(applicationContext, options)
```

Order matters. The registry is not persisted, and until it is registered the implicit default has an empty `optOut`, which the cluster reads as nothing blocked. `initialize` can emit `Application Opened` / `Session Started` during its background setup, so registering afterwards races those events on every launch where the user has not yet set consent.

Keys are trimmed and lowercased, blank keys dropped, and duplicates keep their first occurrence, each with a warning. On a **debuggable build** any of those throws `IllegalArgumentException` at the registration itself; release builds degrade with the warning.

Registration is what lets the SDK complete a sparse CMP payload into a full block: known keys keep their value, unknown keys are dropped, missing categories take their registered default. Re-registering replaces the set and recomputes the view of any stored consent against it — the stored snapshot itself is kept whole, so a category one build drops and the next re-adds comes back with the user's original choice.

### 2. Push consent whenever it changes

```kotlin
analytics.setConsent(Consent(
    explicit = true,
    optOut = mapOf("c0002" to false, "c0004" to true)
))
```

`setConsent` fully replaces the previous state, persists it across launches, and takes effect immediately. Events are stamped when they are enriched, which happens in the background right after `track()` returns, so set consent *before* tracking the events it should cover; `track(); setConsent()` on adjacent lines can stamp the new state on that event. Before the client is ready, calls are queued and enriched when it binds, so every queued event carries the consent current at bind, not at its call. It is safe to call before `initialize` (CMP callbacks often fire first), and the state survives both `analytics.reset()` and `MetaRouter.Analytics.reset()` + re-initialize: consent is a compliance record scoped to the device and person, not to the analytics identity, so a logout must not resurrect tracking the user opted out of. Only `clearConsent()` removes it.

Each `Consent` carries an optional epoch-millisecond `timestamp`. If you supply timestamps (for example from the CMP's own record), a snapshot older than the newest timestamp you have supplied is ignored, so a CMP replaying a cached choice cannot overwrite a fresher one; `setConsent` returns `false` when that happens. `clearConsent()` resets that history, so after a clear any snapshot applies — do not re-push one from before the clear. Use one unit: a single millisecond timestamp followed by seconds rejects every seconds call until the next clear. A call without a timestamp is always applied as the user's current choice; the SDK stamps the device clock for information only and never compares it with yours. The timestamp never goes on the wire.

Before the first `setConsent`, `getConsent()` and every outbound event carry the **implicit default** — `explicit: false` with every registered category at its default — which the cluster gates exactly like an implicit-consent web visitor. There is no "unknown" state.

**Startup initializer.** Consent needs a `Context` to reach disk before `initialize`, so the SDK registers `MetaRouterInitializer` with AndroidX App Startup, which runs it before `Application.onCreate` through the `InitializationProvider` AndroidX libraries already share. It only opens the SDK's storage. To opt out, remove it in your manifest:

```xml
<!-- needs xmlns:tools="http://schemas.android.com/tools" on your <manifest> -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <meta-data
        android:name="com.metarouter.analytics.MetaRouterInitializer"
        tools:node="remove" />
</provider>
```

The SDK then opens storage at `initialize` instead, and consent calls made earlier are held in memory and applied then. Events are unaffected either way, but until `initialize` the previous launch's consent is not readable, a timestamped `setConsent` older than it returns `true` and is ignored when it loads, a registration problem throws at `initialize` rather than at the call, and nothing set before `initialize` is saved if the process never reaches it.

The initializer runs on the main thread before `Application.onCreate` in every process start: it reads the SDK's small preferences file and initializes the `MetaRouter` object. A host that enables StrictMode with `penaltyDeath` for disk reads in `attachBaseContext` (before providers run) will see that read; remove the initializer as above if that matters to you. App Startup only runs in your app's default process, so in a secondary process (`android:process=…`) the SDK opens storage at `initialize`, as when the initializer is removed.

### OneTrust

`OneTrustAdapter` translates OneTrust's opt-in statuses into the canonical shape: category IDs lowercased (`C0004` → `c0004`), values inverted to opt-out semantics. It is a pure function with no dependency on the OneTrust SDK — read OneTrust however you already do and hand the raw result over. Reconciliation against your registered categories happens in `setConsent`, so you can pass everything OneTrust reports.

```kotlin
import com.metarouter.analytics.consent.adapters.OneTrustAdapter

// From the OneTrust SDK's category statuses
val statuses = mapOf("C0001" to 1, "C0002" to 1, "C0004" to 0)
analytics.setConsent(OneTrustAdapter.normalize(statuses, explicit = true))

// From the OptanonConsent cookie's `groups` parameter (e.g. read out of a WebView).
// Percent-encoded values and the whole cookie value are both accepted.
analytics.setConsent(OneTrustAdapter.normalize(groups = "C0001%3A1%2CC0002%3A1%2CC0004%3A0", explicit = true))
```

Pass `explicit = true` from the banner-interaction callback and `explicit = false` when applying a regional default — the payload does not say which it was; your code does. Re-run the two lines on every OneTrust consent-changed callback. If the cookie is absent, do not call `setConsent`: `normalize(groups = "", explicit = true)` reads as "explicitly opted out of everything", which records a rejection the user never made. Any other CMP (or your own preference screen) can build a `Consent` directly: adapters are a convenience, not a requirement.

### Observing and withdrawing

```kotlin
val subscription = analytics.subscribeToConsent { consent ->
    // Runs on the main thread: UI is safe to touch directly. It fires once with the
    // current value (posted to the next main-looper turn), then on every real change.
    thirdPartySdk.setEnabled(consent.optOut["c0004"] == false)
}
// Keep `subscription`: dropping it does not stop delivery. Later, on the main thread —
// nothing is delivered after this returns, even if already queued:
subscription.close()

analytics.clearConsent() // back to the implicit default; persisted state wiped
```

A `setConsent` whose values equal the current state does not notify subscribers, even with a fresher timestamp — handlers react to decisions, not clocks.

## Provider Context

Some destinations will not accept an event unless it carries a vendor's own identifier — one the native app can read but a web page cannot. GA4's app streams, for example, reject app event data without a genuine Firebase app instance id. On the web, sync injectors write values like this into `context.providers`; on mobile, `setProviderContext` is how the app puts them there.

Set a value once and the SDK stamps it onto **every** outbound event — native calls, lifecycle events, and WebView-bridge events alike:

```kotlin
import com.google.firebase.analytics.FirebaseAnalytics

// On every launch, as early as possible. Asynchronous on Android. null while Firebase
// Analytics collection is disabled, and a null value removes the key, so a stale id
// from an earlier launch never lingers.
FirebaseAnalytics.getInstance(this).appInstanceId.addOnSuccessListener { id ->
    MetaRouter.Analytics.shared.setProviderContext("googleFirebase", "appInstanceId", id)
}
```

Every event then carries:

```json
"context": {
  "providers": {
    "googleFirebase": {
      "appInstanceId": "c0ffee1234567890abcdef"
    },
    "metarouter": {
      "sessionID": "1757400000000",
      "sessionCount": 3
    }
  }
}
```

The provider namespace and key are **wire paths, stamped verbatim** apart from trimming surrounding whitespace — no casing or separator normalisation — because pipeline mappings match them exactly. `googleFirebase` / `appInstanceId` above is one worked example, not a special case: the API is agnostic, and any provider and key your pipeline reads will do.

```kotlin
// Several keys under one provider, and several providers, are all fine.
analytics.setProviderContext("googleFirebase", "nonPersonalizedAds", false)
analytics.setProviderContext("adjust", "adid", adjustAdid)

analytics.getProviderContext()                    // everything you have set
analytics.setProviderContext("adjust", "adid", null) // remove one key
analytics.clearProviderContext("adjust")          // remove one provider's block
analytics.clearAllProviderContext()               // remove everything

// A rejected write (oversize, not JSON, over a cap) returns false and logs.
if (!analytics.setProviderContext("adjust", "attribution", attribution)) {
    // too big to stamp on every event — send a smaller id instead
}
```

### Lifetime and timing

- **Usable before `initialize`**, and immediately effective — an event tracked on the next line carries the value. Values never wait for a bind, and are never dropped by the pre-initialize call queue.
- **Persisted across launches**, so a value set in one launch is already on the wire for the next launch's first event. The previous launch's values load at `initialize`, before any event is enriched; until then `getProviderContext()` shows only what this process has set.
- **Survives `reset()`**. These are install-scoped vendor identifiers, not user state; a logout must not quietly stop events being forwardable. Use `clearProviderContext` to remove one deliberately. Note that `reset()` clears the advertising ID but leaves a GAID-shaped value you put under a provider alone.

**Set values on every launch, and clear the ones that rotate.** The persisted value is stamped on every event until you overwrite it, so an id that changes underneath you — Firebase after `resetAnalyticsData()`, an advertising id the user resets — goes out stale with nothing to flag it. Re-set each value on launch, and call `clearProviderContext` on logout or ad-id reset for privacy-sensitive values.

Set each value as early as you have it, ideally before `initialize`. Values are stamped from the moment they are set, and `initialize` emits `Application Opened` (and can mint the first session) during its background setup — so a value that first resolves after that point misses those few events on the install launch. From the next launch onward the persisted value covers them. Firebase's id is asynchronous on Android, so on the install launch it usually arrives after those first events.

### Limits

Because this map rides on every event, it is bounded: at most **10 providers**, **10 keys** per provider, **256 bytes** per encoded value (as JSON), and **64 bytes** per name (UTF-8). Names are trimmed, and must not be blank. The `metarouter` provider is reserved for the SDK's own session block, in any casing or with separators (`Meta_Router`). Values must be JSON-representable all the way down — strings, numbers (finite), booleans, and lists or string-keyed maps of those: a map holding a `Date` is rejected whole.

A write that breaks one of these rules is **ignored**: `setProviderContext` returns `false` and logs a warning, and prior state stays untouched. It never throws, even on debuggable builds, because these values often come from third-party SDKs you do not control. Nothing is silently truncated: a trimmed vendor id looks valid and matches nothing downstream, which is worse than an absent one. `getDebugInfo()` reports the shape (providers and their key names) without the values, since these are device identifiers and debug info lands in host logs.

## WebView Bridge

Hybrid apps render some screens as web content inside a WebView. Without extra work that activity is invisible to MetaRouter — or worse, double-tracked by an in-page analytics setup on a separate identity. The webview bridge captures events fired by page JavaScript and delivers them through the native SDK, so webview and native activity arrive at your cluster as **one user, one pipeline**: native identity, device context, and the page's own facts on every event.

### Attaching

The SDK never discovers WebViews on its own — the host attaches each one explicitly, at creation time, **before** `loadUrl` (the registrations only affect page loads that start afterwards):

```kotlin
val webView = WebView(context)
analytics.attachWebView(webView, allowedOrigins = listOf("https://www.example.com"))
webView.loadUrl("https://www.example.com/checkout")
```

Origins must be explicit `scheme://host[:port]` rules — no wildcards, paths, or trailing slashes. Origin scoping is the bridge's security boundary: it is what keeps arbitrary pages (ads, redirects) loaded in the same WebView from injecting events into your event stream.

### What the page sees

Pages loaded from an allowed origin get a `window.metarouterBridge` object with two methods. Properties may be a plain object or a JSON string of one:

```js
window.metarouterBridge.page('page_view', { section: 'checkout' });
window.metarouterBridge.track('product_viewed', JSON.stringify({ sku: 'SKU-123' }));
```

A typical integration is a small shim in the page's tag manager, branching page views to `page()` and everything else to `track()`:

```js
function logEvent(name, params) {
  var value = JSON.stringify(params);
  if (!window.metarouterBridge) return; // origin not allowed, or old WebView provider
  if (name === 'page_view') {
    window.metarouterBridge.page(name, value);
  } else {
    window.metarouterBridge.track(name, value);
  }
}
```

### What arrives at the cluster

Bridged events go through the same enrichment as native events. The page contributes only what native cannot know; native contributes everything durable:

| From the page (stamped at call time) | From the SDK (merged at receipt) |
|---|---|
| event type (`track`/`page`), name, properties | `anonymousId` / `userId`, device + app context |
| `context.page` — url, path, search, title, referrer | `messageId`, `timestamp`, `context.consent`, session (`context.providers.metarouter`), and any [provider context](#provider-context) the app has set |

Each bridge message carries a producer-minted ID used to drop duplicate deliveries (bounded window). The page receives an ack or a coded error for every message on the underlying channel — malformed input is rejected, never silently dropped, and events arriving while the SDK cannot accept them are answered `not_ready` rather than acked.

### Lifecycle

Attachment is per-WebView and lives as long as the WebView does:

- Attaching before SDK initialization completes is safe — registration happens immediately, so the first page load is captured.
- `reset()` + re-initialization does **not** require re-attaching: an attached WebView keeps delivering to the current client.
- A page loaded *before* attach is never captured — that page's JS world has no bridge. Attach first, then load.
- Destroying the WebView tears everything down; there is no detach API because the platform offers no listener removal.

### Debugging

- `adb logcat -s MetaRouter` shows attach confirmations, rejected messages with their error codes, and duplicate drops — the primary debugging surface.
- From Chrome DevTools (`chrome://inspect` → the WebView), check `typeof window.metarouterBridge` to verify injection, and read the native replies live with `window.__metaRouterNativeChannel.onmessage = e => console.log(e.data)`.
- On devices whose WebView provider lacks the required features, attach logs a warning and captures nothing — it never falls back to an unscoped mechanism.

### Why no auto-attach?

- **The host owns the WebView.** Auto-discovering WebViews would require instrumentation the SDK deliberately avoids, and would capture WebViews (login pages, third-party content) the host never intended to track.
- **Origins are a policy decision.** Only the host knows which origins are its own content; a default allowlist would be either useless or unsafe.
- **Timing is load-bearing.** Registrations only affect subsequent page loads, so the attach point must sit in the host's WebView-creation path — exactly where an explicit call naturally lives.

## Using the alias() Method

The `alias()` method connects an **anonymous user** (tracked by `anonymousId`) to a **known user ID**. It's used to link pre-login activity to post-login identity.

### When to Use alias()

Use `alias()` when a user **signs up** or **logs in for the first time**, and you want to connect their pre-login browsing activity to their new account.

**Primary use case:** Connecting anonymous browsing sessions to newly created user accounts.

### How It Works

```kotlin
analytics.alias("newUserId")
```

This does two things:

1. Sets the new `userId` (same as `identify()`)
2. Sends an `alias` event to your analytics backend, telling it: "This anonymousId and this userId are the same person"

### Example: User Sign-Up Flow

```kotlin
// App starts - user is anonymous
val analytics = MetaRouter.Analytics.initialize(context, options)
// anonymousId: "abc-123" (auto-generated)

// User browses anonymously
analytics.track("Product Viewed", "productId" to "XYZ")
analytics.track("Add to Cart", "productId" to "XYZ")
// Both events tracked with anonymousId: "abc-123"

// User creates an account / signs up
analytics.alias("user-456")
// Sends alias event connecting: anonymousId "abc-123" → userId "user-456"

// Optionally add user traits
analytics.identify("user-456",
    "name" to "John Doe",
    "email" to "john@example.com"
)

// Future events now tracked as authenticated user
analytics.track("Purchase Complete", "orderId" to "789")
// Event includes: userId: "user-456", anonymousId: "abc-123"
```

### alias() vs identify()

| Method           | When to Use                                                     | What It Does                                                   |
| ---------------- | --------------------------------------------------------------- | -------------------------------------------------------------- |
| **`alias()`**    | **First-time sign-up/login** when connecting anonymous activity | Sets userId + sends `alias` event to link anonymousId → userId |
| **`identify()`** | Subsequent logins or updating user traits                       | Sets userId + sends `identify` event with user traits          |

### Best Practices

1. **First-time sign-up:** Call `alias()` to connect anonymous activity to the new account
2. **Subsequent logins:** Use `identify()` — no need to alias again
3. **Backend support:** Ensure your analytics backend supports alias events for merging user profiles
4. **One-time operation:** You typically only need `alias()` once per user — when they first create an account

### Real-World Example: E-Commerce App

```kotlin
// Day 1: Anonymous browsing
analytics.track("App Opened")
analytics.track("Product Viewed", "sku" to "SHOE-123")
analytics.track("Product Viewed", "sku" to "SHIRT-456")
// All tracked with anonymousId: "anon-xyz"

// User signs up
analytics.alias("user-789")
analytics.identify("user-789",
    "name" to "Jane Doe",
    "email" to "jane@example.com"
)

// User continues shopping (now authenticated)
analytics.track("Added to Cart", "sku" to "SHIRT-456")
analytics.track("Purchase", "total" to 49.99)

// Your analytics platform can now show the complete customer journey:
// - Pre-signup activity (anonymous product views)
// - Post-signup activity (cart additions, purchase)
// - Full conversion funnel from anonymous → identified → converted
```

## Advertising ID (GAID)

The SDK supports including the Google Advertising ID (GAID) in event context for ad tracking and attribution purposes.

### Usage

Use the `setAdvertisingId()` method to set the advertising identifier after initializing the analytics client:

```kotlin
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
    )
)

// Set advertising ID after initialization
analytics.setAdvertisingId("your-advertising-id")
```

Once set, the `advertisingId` will be automatically included in the device context of all subsequent events:

```json
{
  "context": {
    "device": {
      "advertisingId": "your-advertising-id",
      "manufacturer": "Samsung",
      "model": "Galaxy S24",
      "type": "android"
    }
  }
}
```

### Privacy & Compliance

**Important**: Advertising identifiers are Personally Identifiable Information (PII). Before collecting advertising IDs, you must:

1. **Obtain User Consent**: Request explicit permission from users before tracking
2. **Comply with Regulations**: Follow GDPR, CCPA, and other applicable privacy laws
3. **Google Play Requirements**: Follow Google Play's [advertising ID policies](https://support.google.com/googleplay/android-developer/answer/6048248)

### Android Example (with Google Play Services)

```kotlin
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Initialize analytics first
val analytics = MetaRouter.Analytics.initialize(
    context = applicationContext,
    options = InitOptions(
        writeKey = "your-write-key",
        ingestionHost = "https://your-ingestion-endpoint.com",
    )
)

// Fetch GAID on a background thread
lifecycleScope.launch {
    val adInfo = withContext(Dispatchers.IO) {
        AdvertisingIdClient.getAdvertisingIdInfo(applicationContext)
    }

    if (!adInfo.isLimitAdTrackingEnabled) {
        analytics.setAdvertisingId(adInfo.id)
    }
}
```

> **Note:** You'll need to add the Google Play Services Ads Identifier dependency:
> ```kotlin
> implementation("com.google.android.gms:play-services-ads-identifier:18.1.0")
> ```

### Clearing Advertising ID

When users opt out of ad tracking or revoke consent, use `clearAdvertisingId()` to remove the advertising ID from storage and context:

```kotlin
// User opts out of ad tracking
analytics.clearAdvertisingId()

// All subsequent events will not include advertisingId in context
analytics.track("Event After Opt Out")
```

**Note:** The `reset()` method also clears the advertising ID along with all other analytics data.

### Validation

The SDK validates advertising IDs before setting them:

- Must be a non-empty string
- Cannot be only whitespace
- Invalid values are rejected and logged as warnings

## License

MIT
