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
    implementation("com.github.metarouterio:android-sdk:1.0.2")
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

**Proxy behavior (quick notes):**

- Buffer is **in-memory only** (not persisted). Calls made before ready are lost if the process exits.
- Ordering is preserved relative to other buffered calls; normal FIFO + batching applies after ready.
- On fatal config errors (`401/403/404`), the client enters **disabled** state and drops subsequent calls.
- `sentAt` is stamped when the batch is prepared for transmission (just before network send). If you need the original occurrence time, pass your own `timestamp` on each event.

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
- `setTracing(enabled: Boolean)`: Enable or disable tracing headers on API requests. When enabled, includes a `Trace: true` header for debugging request flows
- `flush()`: Flush events immediately (suspending)
- `reset()`: Reset analytics state and clear all stored data (suspending). Also available as fire-and-forget via `MetaRouter.Analytics.reset()`
- `enableDebugLogging()`: Enable debug logging
- `getDebugInfo()`: Get current debug information (suspending)

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

Queue capacity: The SDK keeps up to 2,000 events in memory. When the cap is reached, the oldest events are dropped first (drop-oldest). You can change this via `maxQueueEvents` in `InitOptions`.

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
    "library": { "name": "metarouter-android-sdk", "version": "1.0.2" },
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
- Clears event queue
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
