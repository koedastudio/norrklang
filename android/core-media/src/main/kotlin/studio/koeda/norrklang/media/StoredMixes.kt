package studio.koeda.norrklang.media

import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import studio.koeda.norrklang.data.model.LibraryScope
import studio.koeda.norrklang.data.repo.SnapshotStore
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository

/** Where a [HomeMixesSession] keeps its last snapshot between drives. */
internal interface MixPersistence<S> {
    /** The fingerprint the service refreshes under right now; null when signed out. */
    suspend fun currentFingerprint(): String?
    suspend fun load(fingerprint: String): S?
    fun save(fingerprint: String, snapshot: S)
}

/**
 * The key a mix snapshot belongs to: the account plus the raw library
 * selection (no network, and it changes exactly when the user changes it).
 */
internal suspend fun mixFingerprint(account: ProviderSession, settings: ServerSettingsRepository): String =
    account.cacheFingerprint + "/" + LibraryScope.exclusionKey(settings.excludedLibraryIds.first())

/** [MixPersistence] in the account's [SnapshotStore] folder, one file per section [name]. */
internal class StoredMixes<S>(
    private val store: SnapshotStore,
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val name: String,
    private val serializer: KSerializer<S>,
) : MixPersistence<S> {

    override suspend fun currentFingerprint(): String? =
        sessionManager.connectedOrNull()?.session?.let { mixFingerprint(it, settings) }

    override suspend fun load(fingerprint: String): S? =
        store.read(fingerprint.substringBefore('/'), name, fingerprint, serializer)

    override fun save(fingerprint: String, snapshot: S) =
        store.write(fingerprint.substringBefore('/'), name, fingerprint, serializer, snapshot)
}

/** The stored snapshot of one artist-mix section; [name] is its file name in the account folder. */
internal fun storedArtistMixes(
    store: SnapshotStore,
    sessionManager: SessionManager,
    settings: ServerSettingsRepository,
    name: String,
): MixPersistence<List<ArtistMixesSession.Mix>> =
    StoredMixes(store, sessionManager, settings, name, ListSerializer(ArtistMixesSession.Mix.serializer()))
