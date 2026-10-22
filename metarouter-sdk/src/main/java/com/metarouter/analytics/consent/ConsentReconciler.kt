package com.metarouter.analytics.consent

import java.util.Collections

/**
 * Reconciles an incoming consent snapshot against the registered category set.
 *
 * Reconciliation *completes* the block so every outbound event carries a full,
 * uniform consent map — known categories keep their incoming value, unknown keys are
 * dropped, missing categories get their registered default. It never gates delivery:
 * events send regardless, and the server decides per integration.
 */
internal object ConsentReconciler {

    /**
     * @param incoming Consent as supplied by the host (already adapter-normalized).
     * @param registry Registered categories, already deduplicated by the manager. An
     *   empty registry passes every `optOut` value through: with nothing to reconcile
     *   against, dropping keys would silently discard the host's signal. The caller
     *   owns warning about that path.
     *
     * Incoming keys are canonicalized first, on both paths. The registry is lowercase,
     * so a host calling `setConsent` with OneTrust's native `C0004` would otherwise
     * miss the lookup and get the registered default — silently dropping an opt-out
     * whenever that default is opt-in.
     */
    fun reconcile(incoming: Consent, registry: List<ConsentCategory>): Consent {
        val optOut = ConsentKey.canonicalize(incoming.optOut)
        if (registry.isEmpty()) {
            return incoming.copy(optOut = readOnly(optOut))
        }
        val completed = registry.associate { category ->
            category.key to (optOut[category.key] ?: category.defaultOptOut)
        }
        return incoming.copy(optOut = readOnly(completed))
    }

    /**
     * The state events carry before the host ever calls `setConsent`: not an "unknown"
     * marker but a real block — `explicit: false` with every registered category at its
     * default — so the server gates it exactly like an implicit-consent web visitor.
     */
    fun implicitDefault(registry: List<ConsentCategory>): Consent = Consent(
        explicit = false,
        optOut = readOnly(registry.associate { it.key to it.defaultOptOut })
    )

    /**
     * Every Consent the manager hands out is built here, and the same instance is what
     * enrichment stamps. Kotlin's Map is read-only only at compile time: a Java host
     * calling `getConsent().getOptOut().put(…)` would otherwise rewrite the state every
     * later event carries.
     */
    private fun readOnly(optOut: Map<String, Boolean>): Map<String, Boolean> =
        Collections.unmodifiableMap(optOut)
}
