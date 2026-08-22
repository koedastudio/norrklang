package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository

class ResumptionQueueLoaderTest {

    private fun loader(repository: MusicRepository) = ResumptionQueueLoader(
        settings = defaultSettingsRepository(),
        repository = repository,
        randomMix = RandomMixSession(repository),
        similarMixes = SimilarMixesSession(repository),
        bestOfMixes = BestOfMixesSession(repository),
        catalogMixes = CatalogMixesSession(repository),
    )

    @Test
    fun `song radio resumes as the saved track first over fresh similars`() = runTest {
        val fresh = listOf(stubTrack("s-1", "ar-1"), stubTrack("tr-9", "ar-2"), stubTrack("s-2", "ar-3"))
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) =
                if (artistId == "ar-seed") fresh else emptyList()
            override suspend fun track(id: String) = stubTrack(id, "ar-2")
        }

        val queue = loader(repo).resumeTracks(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")

        // The saved track leads and its duplicate in the fresh batch is dropped.
        assertEquals(listOf("tr-9", "s-1", "s-2"), queue.map { it.id })
    }

    @Test
    fun `song radio with no similarity data resumes just the saved track`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) = emptyList<Track>()
            override suspend fun track(id: String) = stubTrack(id)
        }
        assertEquals(
            listOf("tr-9"),
            loader(repo)
                .resumeTracks(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")
                .map { it.id },
        )
    }

    @Test
    fun `song radio rebuild path serves a fresh similar queue`() = runTest {
        val fresh = listOf(stubTrack("s-1", "ar-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) = fresh
        }
        assertEquals(fresh, loader(repo).containerTracks(MediaId.SongRadio("ar-seed")))
    }

    @Test
    fun `a failing radio resume propagates for load's broad catch`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
                throw MusicException.NetworkError(RuntimeException("offline"))
        }
        val thrown = runCatching {
            loader(repo).resumeTracks(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")
        }.exceptionOrNull()
        assertEquals(MusicException.NetworkError::class, thrown!!::class)
    }
}
