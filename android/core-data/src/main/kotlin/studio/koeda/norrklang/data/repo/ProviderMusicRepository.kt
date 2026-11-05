package studio.koeda.norrklang.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.model.LibraryScope
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository

/**
 * What every provider backend shares: the per-account TTL cache, the session
 * lookup, the library scope, and the [MusicException] boundary around each
 * call. Subclasses add the provider's endpoints on top of [cached]/[scoped]
 * (served from the cache) and [withSession]/[withScope] (always live).
 *
 * Every helper reads the session once and hands that same snapshot to its
 * loader: a re-read could store one account's data under another's
 * fingerprint if an account switch landed between the two reads.
 */
abstract class ProviderMusicRepository<S : ProviderSession>(
    private val sessionManager: SessionManager,
    protected val settings: ServerSettingsRepository,
    scope: CoroutineScope,
) : MusicRepository {

    protected val cache = TtlCache(ttlMillis = CACHE_TTL_MS)

    init {
        // Explicit sign-out drops cached library data. Direct account switches
        // use different fingerprint keys, so they cannot reuse another account's
        // data; the TTL prunes those retired keys as new requests arrive.
        // Connected states are left alone: clearing on connect can invalidate
        // that account's first load.
        scope.launch {
            sessionManager.state.drop(1).collect { state ->
                if (state is SessionManager.SessionState.SignedOut) cache.clear()
            }
        }
    }

    /** [session] as this provider's own type, or null when another provider is signed in. */
    protected abstract fun sessionOf(session: ProviderSession): S?

    /** The provider's exception mapped to the neutral hierarchy; null for anything else. */
    protected abstract fun translate(e: Exception): MusicException?

    /** The server's music libraries, straight from the server. */
    protected abstract suspend fun loadLibraries(session: S): List<MusicLibrary>

    /** The libraries browse, search and mix calls cover: the server's list minus the exclusion setting. */
    protected open suspend fun scope(session: S): LibraryScope =
        LibraryScope.resolve(librariesFor(session), settings.excludedLibraryIds.first())

    protected fun session(): S =
        sessionManager.connectedOrNull()?.session?.let(::sessionOf)
            ?: throw MusicException.AuthFailed("Not signed in")

    override suspend fun libraries(): List<MusicLibrary> = librariesFor(session())

    /**
     * Cached per account. The one-shot legacy library seed runs on each
     * load, which is the first time the server's current ids are known.
     */
    protected suspend fun librariesFor(session: S): List<MusicLibrary> =
        cache.getOrLoad("${session.cacheFingerprint}/libraries") {
            translatingErrors(session) { loadLibraries(session) }
                .also { settings.migrateLegacyLibrarySelection(it.map { library -> library.id }) }
        }

    /** Unscoped cache: by-id lookups and anything that must ignore the library selection. */
    protected suspend fun <T : Any> cached(key: String, loader: suspend (S) -> T): T {
        val session = session()
        return cache.getOrLoad("${session.cacheFingerprint}/$key") {
            translatingErrors(session) { loader(session) }
        }
    }

    /** Selection-scoped cache: the scope key in the entry key replaces explicit invalidation. */
    protected suspend fun <T : Any> scoped(key: String, loader: suspend (S, LibraryScope) -> T): T {
        val session = session()
        val scope = scope(session)
        return cache.getOrLoad("${session.cacheFingerprint}/${scope.key}/$key") {
            translatingErrors(session) { loader(session, scope) }
        }
    }

    /** Uncached, unscoped: writes and playback reports. [expectedSession] rejects a replaced sign-in. */
    protected suspend fun <T> withSession(
        expectedSession: ProviderSession? = null,
        block: suspend (S) -> T,
    ): T {
        val session = session()
        if (expectedSession != null && session !== expectedSession) {
            throw MusicException.AuthFailed("Playback account changed")
        }
        return translatingErrors(session) { block(session) }
    }

    /** Uncached, selection-scoped: the random and similar draws. */
    protected suspend fun <T> withScope(block: suspend (S, LibraryScope) -> T): T {
        val session = session()
        val scope = scope(session)
        return translatingErrors(session) { block(session, scope) }
    }

    /** Nothing above core-data sees a provider exception. An auth rejection also flips the session state. */
    private suspend fun <T> translatingErrors(session: S, block: suspend () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            val translated = translate(e) ?: throw e
            if (translated is MusicException.AuthFailed) sessionManager.onAuthRejected(session)
            throw translated
        }

    override fun invalidateCache() = cache.clear()

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
