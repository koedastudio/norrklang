package studio.koeda.norrklang.media.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.repo.SnapshotStore
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.media.BestOfMixesSession
import studio.koeda.norrklang.media.CatalogMixes
import studio.koeda.norrklang.media.RandomMixSession
import studio.koeda.norrklang.media.SimilarMixesSession
import studio.koeda.norrklang.media.storedArtistMixes

@Module
@InstallIn(SingletonComponent::class)
internal object MediaModule {

    // Singleton so ArtworkProvider — a separate component with no service
    // reference — renders the tile collage from the same snapshot the browse
    // view serves.
    @Provides
    @Singleton
    fun randomMixSession(
        repository: MusicRepository,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
    ): RandomMixSession = RandomMixSession(repository).also { mix ->
        // One account's tracks must not survive a sign-out or account switch
        // (mirrors ProviderMusicRepository.init).
        scope.launch {
            sessionManager.state.collect { mix.clear() }
        }
    }

    @Provides
    @Singleton
    fun similarMixesSession(
        repository: MusicRepository,
        bestOfMixes: BestOfMixesSession,
        sessionManager: SessionManager,
        settings: ServerSettingsRepository,
        store: SnapshotStore,
        @ApplicationScope scope: CoroutineScope,
    ): SimilarMixesSession =
        SimilarMixesSession(
            repository,
            // No artist should head both a "Best of" and a "Similar to" tile;
            // the service refreshes Best of first so these seeds are settled.
            excludedSeedIds = { bestOfMixes.currentMixes().map { it.artist.id }.toSet() },
            persistence = storedArtistMixes(store, sessionManager, settings, "mixes-similar"),
        ).clearingOnSignOut(sessionManager, scope) { clear() }

    @Provides
    @Singleton
    fun bestOfMixesSession(
        repository: MusicRepository,
        sessionManager: SessionManager,
        settings: ServerSettingsRepository,
        store: SnapshotStore,
        @ApplicationScope scope: CoroutineScope,
    ): BestOfMixesSession =
        BestOfMixesSession(repository, storedArtistMixes(store, sessionManager, settings, "mixes-bestof"))
            .clearingOnSignOut(sessionManager, scope) { clear() }

    @Provides
    @Singleton
    fun catalogMixes(
        repository: MusicRepository,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
    ): CatalogMixes = CatalogMixes(repository).clearingOnSignOut(sessionManager, scope) { clear() }

    /**
     * Drops the session's tracks when the account is no longer connected. Non-Connected states only: the
     * service's Connected branch generates on the same emission, and an
     * unconditional clear would race it (StateFlow collectors run in
     * undefined order). Connected → Connected account switches are covered
     * by the session's fingerprint stamp.
     */
    private fun <T> T.clearingOnSignOut(
        sessionManager: SessionManager,
        scope: CoroutineScope,
        clear: suspend T.() -> Unit,
    ): T = also { session ->
        scope.launch {
            sessionManager.state.collect { state ->
                if (state !is SessionManager.SessionState.Connected) session.clear()
            }
        }
    }
}
