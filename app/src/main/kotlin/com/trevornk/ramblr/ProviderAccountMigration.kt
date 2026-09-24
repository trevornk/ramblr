package com.trevornk.ramblr

import android.content.Context
import java.util.UUID

/**
 * One-time, idempotent, crash-safe migration onto the #274 provider-accounts model: assigns a
 * stable [ProviderChainEntry.id] to every existing entry that doesn't have one yet, then copies
 * each kind's legacy per-kind credential ([ProviderCredentialStore.getLegacyByKind]) onto every
 * existing entry of that kind that doesn't yet have its own per-entry credential
 * ([ProviderCredentialStore.get]).
 *
 * ## Why this exists (#273/#274)
 *
 * Before #274, [ProviderCredentialStore] had exactly one secret slot per [ProviderKind]. Once
 * credentials moved to per-entry slots keyed by [ProviderChainEntry.id], every entry saved before
 * this release needs two things it doesn't have: an id, and its own copy of whatever key used to
 * live in the kind-wide slot. Losing a real key on a real device (Trevor's) during this upgrade is
 * the worst possible outcome, so this migration is built to be safe under every interruption:
 *
 *  - **Ids are written before keys.** [runIfNeeded] persists the id-assigned chain first, then
 *    copies keys as a separate step. A process death between those two steps leaves a chain whose
 *    entries already have ids and whose keys just haven't been copied yet -- the next run picks up
 *    exactly there, because id assignment is a no-op for entries that already have one.
 *  - **Every step is naturally idempotent.** [assignMissingIds] only touches entries with a blank
 *    id, so re-running it after ids exist changes nothing. [legacyKeyCopyPlan] only proposes a
 *    write for an entry that doesn't already have its own key, so re-running it after a key has
 *    been copied proposes nothing further for that entry.
 *  - **The legacy per-kind slots are never deleted here.** They stay in place as a recovery
 *    source (see [ProviderCredentialStore]'s kdoc) -- a deliberate scope cut for this release; a
 *    follow-up can retire them once this migration is confirmed to have run cleanly in the field.
 *  - **A version gate makes a normal (already-migrated) launch cheap** -- one int read, no chain
 *    reload -- without weakening the crash-safety above, since the gate is only an optimization:
 *    even if [runIfNeeded] ran unconditionally on every launch, the result would be identical.
 *
 * [assignMissingIds] and [legacyKeyCopyPlan] are pure functions over plain values (no
 * [android.content.Context]) so the actual migration decisions are JVM-testable without Android;
 * [runIfNeeded] is the thin, untested-by-necessity Android glue that sequences them against real
 * storage.
 */
object ProviderAccountMigration {
    private const val PREFS_NAME = "ramblr"
    private const val KEY_MIGRATION_VERSION = "provider_account_migration_version"
    private const val MIGRATION_VERSION = 1

    /**
     * Assigns a fresh id (via [newId]) to every entry in [entries] whose [ProviderChainEntry.id]
     * is blank, leaving every other field of that entry -- and every already-id'd entry -- byte
     * for byte untouched. Idempotent: an entries list where every id is already non-blank is
     * returned with the exact same field values (new [List] instance, but `==` to the input).
     */
    fun assignMissingIds(
        entries: List<ProviderChainEntry>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<ProviderChainEntry> =
        entries.map { entry -> if (entry.id.isBlank()) entry.copy(id = newId()) else entry }

    /**
     * Pure core of the credential copy: given [entryKindsMissingOwnKey] (entryId -> kind, for
     * every entry that -- per a real per-entry [ProviderCredentialStore] lookup the Android
     * caller already did -- does not yet have its own credential) and [legacyKeysByKind] (each
     * kind's current legacy per-kind key value, blank/absent if never configured), returns the
     * entryId -> key-value writes to perform.
     *
     * Only entries with no key of their own are ever included in the input, so this can never
     * overwrite a key the user already set for a specific entry (e.g. after adding a second
     * same-kind entry with a different key post-upgrade) -- it only ever fills a genuine gap. A
     * kind with no legacy key (blank or missing from [legacyKeysByKind]) contributes no writes for
     * any of its entries, so a kind nobody ever configured stays unconfigured for every entry
     * of that kind, exactly as before.
     */
    fun legacyKeyCopyPlan(
        entryKindsMissingOwnKey: Map<String, ProviderKind>,
        legacyKeysByKind: Map<ProviderKind, String>,
    ): Map<String, String> =
        entryKindsMissingOwnKey.mapNotNull { (entryId, kind) ->
            val legacyKey = legacyKeysByKind[kind]
            if (!legacyKey.isNullOrBlank()) entryId to legacyKey else null
        }.toMap()

    /**
     * Runs the migration at most as often as a version bump requires it, but is always safe to
     * call more often than that (see class kdoc). Call from the same service-startup path that
     * already runs [ProviderChainMigration.runIfNeeded] (see
     * [WhisperAccessibilityService.onServiceConnected]) -- deliberately a SEPARATE object/version
     * counter from that model-id migration, since the two migrate unrelated things and must be
     * able to evolve (and be diagnosed) independently.
     */
    fun runIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appliedVersion = prefs.getInt(KEY_MIGRATION_VERSION, 0)
        if (appliedVersion >= MIGRATION_VERSION) return

        // Step 1: ids first. Persisted immediately and separately from the key copy below, so a
        // crash right after this line still leaves every entry addressable by a stable id on the
        // next run.
        val chain = ProviderChainStore.load(context)
        val chainWithIds = ProviderChain(assignMissingIds(chain.entries))
        if (chainWithIds != chain) {
            ProviderChainStore.save(context, chainWithIds)
        }

        // Step 2: copy legacy per-kind keys onto every entry of that kind that doesn't have its
        // own key yet. Reads real per-entry/per-kind state through ProviderCredentialStore (the
        // Android-coupled part this function can't avoid), then hands the plain data to the pure
        // legacyKeyCopyPlan to decide what to write.
        val entryKindsMissingOwnKey = chainWithIds.entries
            .filter { it.id.isNotBlank() && !ProviderCredentialStore.isConfigured(context, it) }
            .associate { it.id to it.kind }
        val kindsInvolved = entryKindsMissingOwnKey.values.toSet()
        val legacyKeysByKind = kindsInvolved.associateWith { ProviderCredentialStore.getLegacyByKind(context, it) }
        val writes = legacyKeyCopyPlan(entryKindsMissingOwnKey, legacyKeysByKind)
        writes.forEach { (entryId, key) -> ProviderCredentialStore.set(context, entryId, key) }

        prefs.edit().putInt(KEY_MIGRATION_VERSION, MIGRATION_VERSION).apply()
    }
}
