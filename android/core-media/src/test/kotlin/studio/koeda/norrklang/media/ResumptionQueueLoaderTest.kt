package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ResumptionState

class ResumptionQueueLoaderTest {

    private fun containers(repository: MusicRepository) = ContainerTracks(
        repository,
        RandomMixSession(repository),
        SimilarMixesSession(repository),
        BestOfMixesSession(repository),
        CatalogMixesSession(repository),
    )

    private fun loader(repository: MusicRepository) = ResumptionQueueLoader(
        settings = defaultSettingsRepository(),
        repository = repository,
        containers = containers(repository),
        stationQueue = StationQueue(repository) { station ->
            MediaItem.Builder().setMediaId(MediaId.RadioStation(station.id).encode()).build()
        },
        buildItem = { track, container -> MediaItem.Builder().setMediaId(MediaId.Track(track.id, container).encode()).build() },
    )

    @Test
    fun `a radio station resumes amid all stations from the live edge`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() = listOf(
                RadioStation("rs-1", "KEXP", "https://kexp.example/stream"),
                RadioStation("rs-2", "FIP", "https://fip.example/stream"),
                RadioStation("rs-3", "P3", "https://p3.example/stream"),
            )
        }
        val queue = loader(repo).restore(ResumptionState(MediaId.RadioStation("rs-2").encode(), 90_000))!!
        assertEquals(listOf("station/rs-1", "station/rs-2", "station/rs-3"), queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
        assertEquals(C.TIME_UNSET, queue.startPositionMs)
    }

    @Test
    fun `a station removed from the server resumes nothing`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() = emptyList<RadioStation>()
        }
        assertNull(loader(repo).restore(ResumptionState(MediaId.RadioStation("rs-1").encode(), 0)))
    }

    @Test
    fun `song radio resumes as the saved track first over fresh similars`() = runTest {
        val fresh = listOf(stubTrack("s-1", "ar-1"), stubTrack("tr-9", "ar-2"), stubTrack("s-2", "ar-3"))
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) =
                if (artistId == "ar-seed") fresh else emptyList()
            override suspend fun track(id: String) = stubTrack(id, "ar-2")
        }

        val queue = containers(repo).resume(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")

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
            containers(repo)
                .resume(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")
                .map { it.id },
        )
    }

    @Test
    fun `song radio rebuild path serves a fresh similar queue`() = runTest {
        val fresh = listOf(stubTrack("s-1", "ar-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) = fresh
        }
        assertEquals(fresh, containers(repo).tracks(MediaId.SongRadio("ar-seed")))
    }

    @Test
    fun `a failing radio resume propagates for load's broad catch`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
                throw MusicException.NetworkError(RuntimeException("offline"))
        }
        val thrown = runCatching {
            containers(repo).resume(MediaId.SongRadio("ar-seed"), savedTrackId = "tr-9")
        }.exceptionOrNull()
        assertEquals(MusicException.NetworkError::class, thrown!!::class)
    }

    @Test
    fun `a removed favorite resumes the first remaining track from its beginning`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun favoriteTracks() = listOf(stubTrack("remaining"))
        }
        val queue = loader(repo).restore(ResumptionState(MediaId.Track("removed", MediaId.HomeFavoriteSongs).encode(), 120_000))!!
        assertEquals(0, queue.startIndex)
        assertEquals(0L, queue.startPositionMs)
        assertEquals(MediaId.Track("remaining", MediaId.HomeFavoriteSongs).encode(), queue.mediaItems.single().mediaId)
    }

    @Test
    fun `a saved track still in the container retains its position`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun favoriteTracks() = listOf(stubTrack("first"), stubTrack("saved"))
        }
        val queue = loader(repo).restore(ResumptionState(MediaId.Track("saved", MediaId.HomeFavoriteSongs).encode(), 120_000))!!
        assertEquals(1, queue.startIndex)
        assertEquals(120_000L, queue.startPositionMs)
    }
}
