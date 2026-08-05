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
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.media.BestOfMixesSession
import studio.koeda.norrklang.media.CatalogMixesSession
import studio.koeda.norrklang.media.HomeMixesSession
import studio.koeda.norrklang.media.RandomMixSession
import studio.koeda.norrklang.media.SimilarMixesSession

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
        // The snapshot embeds authenticated stream URLs — never let it survive
        // a sign-out or account switch (mirrors DefaultMusicRepository.init).
        scope.launch {
            sessionManager.state.collect { mix.clear() }
        }
    }

    @Provides
    @Singleton
    fun similarMixesSession(
        repository: MusicRepository,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
    ): SimilarMixesSession =
        SimilarMixesSession(repository).clearingOnSignOut(sessionManager, scope)

    @Provides
    @Singleton
    fun bestOfMixesSession(
        repository: MusicRepository,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
    ): BestOfMixesSession =
        BestOfMixesSession(repository).clearingOnSignOut(sessionManager, scope)

    @Provides
    @Singleton
    fun catalogMixesSession(
        repository: MusicRepository,
        sessionManager: SessionManager,
        @ApplicationScope scope: CoroutineScope,
    ): CatalogMixesSession =
        CatalogMixesSession(repository).clearingOnSignOut(sessionManager, scope)

    /**
     * Drops the session's tracks (they embed authenticated stream URLs) when
     * the account is no longer connected. Non-Connected states only: the
     * service's Connected branch generates on the same emission, and an
     * unconditional clear would race it (StateFlow collectors run in
     * undefined order). Connected → Connected account switches are covered
     * by the session's fingerprint stamp.
     */
    private fun <T : HomeMixesSession<*, *>> T.clearingOnSignOut(
        sessionManager: SessionManager,
        scope: CoroutineScope,
    ): T = also { session ->
        scope.launch {
            sessionManager.state.collect { state ->
                if (state !is SessionManager.SessionState.Connected) session.clear()
            }
        }
    }
}
