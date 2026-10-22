package com.metarouter.analytics.providers

import com.metarouter.analytics.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections

/**
 * Owns the host-set `context.providers` map: in-memory state, bounds, persistence. The
 * public API delegates here; enrichment reads [getAll] once per event.
 *
 * A lock-based class, for the same reason as `ConsentManager`: `setProviderContext`
 * must be read-your-writes synchronous. A host that resolves a vendor id in a callback
 * and tracks on the next line expects that event to carry it.
 *
 * Deliberately not merged with `ConsentManager` despite the shared shape: consent is a
 * reconciled state machine with a registry, subscribers and staleness arbitration;
 * this is a bounded map written verbatim.
 *
 * Storage arrives after construction, like consent's: the process-wide manager is
 * built before any `Context` exists, and [attachStorage] runs at process start through
 * `MetaRouterInitializer` (or at `initialize`, when a host removed it). Until then it
 * runs in memory and replays the writes made before the attach over the persisted map.
 * The client is built after the attach, so last launch's values are on the cold-launch
 * events.
 *
 * @param storage null until [attachStorage]; tests and the client's own fallback pass
 *   one up front.
 */
internal class ProviderContextManager(storage: ProviderContextStorage? = null) {

    /** One host write, as recorded before storage is attached. */
    private sealed class Write {
        /** [value] null removes the key. */
        class Put(val provider: String, val key: String, val value: JsonElement?) : Write()
        class ClearProvider(val provider: String) : Write()
        object ClearAll : Write()
    }

    private enum class Outcome { REJECTED, UNCHANGED, CHANGED }

    private val lock = Any()

    @Volatile
    private var storage: ProviderContextStorage? = storage

    // Loaded eagerly when storage is present, so last launch's values are visible to
    // the very first enrichment.
    private val providers: MutableMap<String, MutableMap<String, JsonElement>> = mutableMapOf()

    /** Immutable view handed to enrichment; rebuilt per commit, not per event. */
    private var snapshot: Map<String, JsonObject> = emptyMap()

    private val preAttachWrites = mutableListOf<Write>()

    // Commit ordering for off-lock persistence, mirroring ConsentManager: a host
    // preference listener runs synchronously inside apply() on the main thread and
    // must not run inside a commit.
    private var commitSequence = 0L
    private val persistLock = Any()
    private var persistedSequence = 0L

    init {
        storage?.get()?.let(::loadLocked)
    }

    // ===== Reads =====

    /** The full map, for enrichment. Immutable, so it can outlive the lock. */
    fun getAll(): Map<String, JsonObject> = synchronized(lock) { snapshot }

    // ===== Writes =====

    /**
     * Host entry point. `null` — and anything converting to JSON null — removes the key
     * rather than stamping a null: a mapping gets nothing useful from a null, and a host
     * passing null means "stop sending this".
     *
     * @return `false` when the write was rejected (with a warning) and prior state
     *   stands; `true` when the state now reflects the call, including the removal of a
     *   key that was not set.
     */
    fun setValue(provider: String, key: String, value: Any?): Boolean {
        val converted = if (value == null) JsonNull else strictlyConverted(value)
        if (converted == null) {
            Logger.warn("setProviderContext(\"$provider\", key: \"$key\"): value is not JSON-representable — ignored")
            return false
        }
        val providerName = provider.trim()
        val keyName = key.trim()

        // NOT lowercased, unlike consent category keys: these are wire paths matched
        // verbatim by pipeline mappings (the Firebase kit reads
        // `context.providers.googleFirebase.appInstanceId`), so canonicalising case
        // would break the mapping this exists to feed.
        if (!validName(providerName, "provider") || !validName(keyName, "key")) return false

        if (isReserved(providerName)) {
            Logger.warn("setProviderContext: \"$providerName\" is reserved for the SDK's own session block — ignored")
            return false
        }

        val element = converted.takeUnless { it is JsonNull }
        if (element != null) {
            oversizeRejection(element, providerName, keyName)?.let {
                Logger.warn(it)
                return false
            }
        }

        return write(Write.Put(providerName, keyName, element))
    }

    /** Removes one provider's whole block. */
    fun clear(provider: String) {
        write(Write.ClearProvider(provider.trim()))
    }

    /**
     * Removes everything the host has set. Distinct from `reset()`, which clears
     * identity and leaves this alone.
     */
    fun clearAll() {
        write(Write.ClearAll)
    }

    private fun write(write: Write): Boolean {
        val pending = synchronized(lock) {
            val outcome = applyLocked(write)
            // Before attach every accepted write is recorded, no-ops included: a clear
            // or removal that finds nothing in memory may still have persisted state to
            // remove once it loads.
            if (outcome != Outcome.REJECTED && storage == null) preAttachWrites += write
            when (outcome) {
                Outcome.REJECTED -> return false
                // No commit on a no-op: bumping the sequence for a write that changed
                // nothing would let it supersede a concurrent real commit on disk.
                Outcome.UNCHANGED -> return true
                Outcome.CHANGED -> commitLocked()
            }
        }
        persist(pending)
        return true
    }

    /**
     * The one mutation path, for host writes and the replay at attach alike. Caller
     * holds [lock]. Removing a provider's last key removes the provider — an empty
     * block would otherwise ship on every event forever, indistinguishable from a
     * misconfigured one.
     */
    private fun applyLocked(write: Write): Outcome = when (write) {
        is Write.Put -> {
            val existing = providers[write.provider]
            if (write.value != null) {
                if (existing == null && providers.size >= MAX_PROVIDERS) {
                    Logger.warn("setProviderContext: at most $MAX_PROVIDERS providers — \"${write.provider}\" ignored")
                    Outcome.REJECTED
                } else if (existing != null && write.key !in existing && existing.size >= MAX_KEYS_PER_PROVIDER) {
                    Logger.warn(
                        "setProviderContext: at most $MAX_KEYS_PER_PROVIDER keys per provider — " +
                            "\"${write.provider}.${write.key}\" ignored"
                    )
                    Outcome.REJECTED
                } else {
                    providers.getOrPut(write.provider) { mutableMapOf() }[write.key] = write.value
                    Outcome.CHANGED
                }
            } else if (existing?.remove(write.key) == null) {
                Outcome.UNCHANGED
            } else {
                if (existing.isEmpty()) providers.remove(write.provider)
                Outcome.CHANGED
            }
        }
        is Write.ClearProvider -> if (providers.remove(write.provider) != null) Outcome.CHANGED else Outcome.UNCHANGED
        Write.ClearAll -> if (providers.isEmpty()) {
            Outcome.UNCHANGED
        } else {
            providers.clear()
            Outcome.CHANGED
        }
    }

    // ===== Storage attach =====

    /**
     * Hands the manager its storage, once: later calls (a re-initialize after
     * `reset()`) are ignored, the manager outlives clients. Loads the persisted map and
     * replays the writes made before this over it, in order and through the same
     * bounds, so the result is what storage present from the start would have held.
     */
    /** Whether [attachStorage] has run, so callers can skip building a storage. */
    val hasStorage: Boolean
        get() = storage != null

    fun attachStorage(newStorage: ProviderContextStorage) {
        if (storage != null) return
        val loaded = newStorage.get()
        val pending = synchronized(lock) {
            if (storage != null) return@synchronized null
            storage = newStorage
            val writes = preAttachWrites.toList()
            preAttachWrites.clear()
            providers.clear()
            loaded?.let(::loadLocked)
            if (writes.isEmpty()) return@synchronized null
            writes.forEach(::applyLocked)
            commitLocked()
        }
        pending?.let(::persist)
    }

    /** Caller holds [lock] (or is the constructor). */
    private fun loadLocked(loaded: Map<String, JsonObject>) {
        for ((provider, keys) in loaded) {
            if (keys.isNotEmpty()) providers[provider] = keys.toMutableMap()
        }
        snapshot = snapshotOf(providers)
    }

    // ===== Validation =====

    private fun validName(name: String, label: String): Boolean {
        if (name.isEmpty()) {
            Logger.warn("setProviderContext: blank $label name — ignored")
            return false
        }
        // UTF-8 bytes, not characters: the budget is a byte budget, and one grapheme
        // can carry hundreds of combining marks.
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            Logger.warn("setProviderContext: $label name exceeds $MAX_NAME_BYTES bytes — ignored")
            return false
        }
        return true
    }

    /**
     * Null when the value fits. Measured as encoded JSON so one rule covers scalars and
     * nested arrays/objects.
     */
    private fun oversizeRejection(value: JsonElement, provider: String, key: String): String? {
        val byteCount = Json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8).size
        if (byteCount <= MAX_VALUE_BYTES) return null
        return "setProviderContext(\"$provider\", key: \"$key\"): value is $byteCount bytes, limit $MAX_VALUE_BYTES — ignored"
    }

    // ===== Persistence =====

    private class PendingPersist(val sequence: Long, val value: Map<String, JsonObject>)

    /** Caller holds [lock] and must hand the result to [persist] after releasing it. */
    private fun commitLocked(): PendingPersist {
        snapshot = snapshotOf(providers)
        commitSequence += 1
        return PendingPersist(commitSequence, snapshot)
    }

    /**
     * Writes a commit unless a later one already has. Two threads can leave the manager
     * lock in the opposite order from their commits; the sequence keeps disk from
     * ending on an older map than memory.
     */
    private fun persist(pending: PendingPersist) {
        synchronized(persistLock) {
            if (pending.sequence <= persistedSequence) return
            persistedSequence = pending.sequence
            // Pre-attach commits have nowhere to go; attachStorage persists their result.
            val target = storage ?: return
            if (pending.value.isEmpty()) target.clear() else target.set(pending.value)
        }
    }

    // ===== Debug =====

    /**
     * Values omitted on purpose: these are vendor device identifiers and getDebugInfo
     * is printed into host logs — the same reason advertisingId is redacted there. The
     * shape is what shows a misconfiguration.
     */
    fun toDebugInfo(): Map<String, List<String>> = getAll().mapValues { (_, keys) -> keys.keys.sorted() }

    // ===== Test seam =====

    /**
     * Swaps the backing storage and rebuilds from it, for tests that exercise the
     * process-wide manager without touching the production preferences file; null
     * returns it to the pre-initialize, unattached state.
     */
    fun replaceStorageForTesting(newStorage: ProviderContextStorage?) {
        val loaded = newStorage?.get()
        synchronized(persistLock) {
            synchronized(lock) {
                storage = newStorage
                preAttachWrites.clear()
                providers.clear()
                snapshot = emptyMap()
                loaded?.let(::loadLocked)
                commitSequence = 0
                persistedSequence = 0
            }
        }
    }

    companion object {
        /**
         * Reserved for the SDK's own block (`sessionID`, `sessionCount`). Enrichment
         * writes it last and would win anyway, but a write that silently vanished later
         * is worse than one rejected at the call. Matched ignoring case and separators,
         * so `Metarouter` or `meta_router` cannot sit beside it looking like ours.
         */
        const val RESERVED_PROVIDER = "metarouter"

        // This map rides on EVERY event and the cluster rejects a single event over
        // 250 KiB. Worst case here is 10 x 10 x (256 + 64 + 64) ≈ 38 KB — far more than
        // any real integration needs (a vendor id is a ~36-byte UUID) and well clear of
        // the cap. Exceeding a bound rejects that one write and keeps prior state; it
        // never trims, because a truncated vendor id looks valid and matches nothing.
        //
        // A rejection warns and returns false — never throws, even on a debuggable
        // build. Values here come from third-party SDKs the host does not control, and
        // a throw would crash the host on vendor data.
        const val MAX_PROVIDERS = 10
        const val MAX_KEYS_PER_PROVIDER = 10
        const val MAX_VALUE_BYTES = 256
        const val MAX_NAME_BYTES = 64

        private fun isReserved(providerName: String): Boolean =
            providerName.lowercase().filter { it.isLetterOrDigit() } == RESERVED_PROVIDER

        /**
         * Read-only, because this exact map is what every event is stamped from and what
         * the next persist writes. Kotlin's Map is read-only only at compile time: a
         * Java host calling `getProviderContext().clear()` would otherwise rewrite them,
         * past every bound. JsonObject itself is immutable.
         */
        private fun snapshotOf(map: Map<String, Map<String, JsonElement>>): Map<String, JsonObject> =
            Collections.unmodifiableMap(map.mapValues { (_, keys) -> JsonObject(keys.toMap()) })

        /**
         * The tolerant `toJsonElement` used for event properties stringifies anything it
         * does not know, which suits properties. Here it would store a value the host
         * never set (`mapOf("id" to "abc", "ts" to Date())` stamping the date's
         * `toString()` on every event), so anything unrepresentable anywhere — a
         * non-string map key, a non-finite number, an arbitrary object — rejects the
         * whole write. Null inside a container is JSON null; null at the top level is
         * removal and never reaches here.
         */
        /**
         * A JsonElement is not automatically valid JSON: `JsonPrimitive(Double.NaN)`
         * encodes as `NaN`, and `JsonUnquotedLiteral` writes its content raw. Either
         * would make every batch body unparseable, so it gets the same checks as
         * converted values: finite numbers, real literals, all the way down.
         */
        // RFC 8259 number grammar. toDoubleOrNull would also accept Java float syntax
        // (`1f`, `0x1p3`, `.5`, `+1`), which is not JSON. The grammar has no NaN or
        // Infinity, so finiteness comes with it.
        private val JSON_NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

        private fun isWireSafe(element: JsonElement): Boolean = when (element) {
            is JsonNull -> true
            is JsonPrimitive -> element.isString ||
                element.content == "true" || element.content == "false" ||
                JSON_NUMBER.matches(element.content)
            is JsonArray -> element.all(::isWireSafe)
            is JsonObject -> element.values.all(::isWireSafe)
        }

        private fun strictlyConverted(value: Any?): JsonElement? = when (value) {
            null -> JsonNull
            is JsonElement -> value.takeIf(::isWireSafe)
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Double -> value.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            is Float -> value.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            is Number -> JsonPrimitive(value)
            is List<*> -> value.map { strictlyConverted(it) ?: return null }.let(::JsonArray)
            is Map<*, *> -> value.entries.associate { (k, v) ->
                if (k !is String) return null
                k to (strictlyConverted(v) ?: return null)
            }.let(::JsonObject)
            else -> null
        }
    }
}
