package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.media.VoiceSearchResolver.Focus
import studio.koeda.norrklang.media.VoiceSearchResolver.Queue
import studio.koeda.norrklang.media.VoiceSearchResolver.Request
import studio.koeda.norrklang.media.VoiceSearchResolver.Station

class VoiceSearchResolverTest {

    private fun resolver(
        repository: MusicRepository,
        containerTracks: suspend (MediaId.Container) -> List<Track> = { emptyList() },
    ) = VoiceSearchResolver(repository, containerTracks)

    /** The track queue a request resolves to; fails the test on a station. */
    private suspend fun VoiceSearchResolver.queue(request: Request): Queue? =
        resolve(request)?.let { it as Queue }

    private fun stubStation(id: String, name: String) =
        RadioStation(id = id, name = name, streamUrl = "https://radio/$id")

    private fun stubArtist(id: String, name: String) =
        Artist(id = id, name = name, albumCount = 1, artworkUrl = null)

    private fun stubAlbum(id: String, title: String, artistName: String? = null) = Album(
        id = id,
        title = title,
        artistName = artistName,
        artistId = null,
        year = null,
        trackCount = 1,
        durationSec = 60,
        artworkUrl = null,
    )

    private fun results(
        artists: List<Artist> = emptyList(),
        albums: List<Album> = emptyList(),
        tracks: List<Track> = emptyList(),
    ) = SearchResults(artists, albums, tracks)

    @Test
    fun `empty query plays the random mix`() = runTest {
        val mix = listOf(stubTrack("tr-1"), stubTrack("tr-2"))
        val queue = resolver(
            FakeMusicRepository(),
            containerTracks = { container ->
                if (container == MediaId.HomeRandomMix) mix else emptyList()
            },
        ).queue(Request(query = "  "))
        assertEquals(mix, queue?.tracks)
        assertEquals(MediaId.HomeRandomMix, queue?.container)
    }

    @Test
    fun `exact track title beats server result order`() = runTest {
        val other = stubTrack("tr-1").copy(title = "Wish You Were Here (Karaoke)")
        val wanted = stubTrack("tr-2").copy(title = "Wish You Were Here")
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) = results(tracks = listOf(other, wanted))
        }
        val queue = resolver(repo).queue(Request(query = "wish you were here"))
        assertEquals(listOf(wanted), queue?.tracks)
        assertNull(queue?.container)
    }

    @Test
    fun `exact artist match wins over a fuzzy track hit`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) = results(
                artists = listOf(stubArtist("ar-1", "The Beatles")),
                tracks = listOf(stubTrack("tr-1").copy(title = "Beatles Medley")),
            )
        }
        val best = listOf(stubTrack("tr-9", artistId = "ar-1"))
        val queue = resolver(
            repo,
            containerTracks = { container ->
                if (container == MediaId.HomeBestOf("ar-1")) best else emptyList()
            },
        ).queue(Request(query = "beatles"))
        assertEquals(best, queue?.tracks)
        assertEquals(MediaId.HomeBestOf("ar-1"), queue?.container)
    }

    @Test
    fun `artist without top tracks falls back to albums in order, capped`() = runTest {
        val albums = (1..7).map { stubAlbum("al-$it", "Album $it") }
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) =
                results(artists = listOf(stubArtist("ar-1", "Kent")))
            override suspend fun artist(id: String) =
                ArtistDetail(stubArtist(id, "Kent"), albums)
            override suspend fun album(id: String) =
                AlbumDetail(stubAlbum(id, "x"), listOf(stubTrack("tr-$id")))
        }
        val queue = resolver(repo).queue(Request(query = "kent", focus = Focus.Artist))
        assertEquals(
            (1..VoiceSearchResolver.ARTIST_ALBUM_LIMIT).map { "tr-al-$it" },
            queue?.tracks?.map { it.id },
        )
    }

    @Test
    fun `album focus prefers the album by the hinted artist`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) = results(
                albums = listOf(
                    stubAlbum("al-1", "Greatest Hits", artistName = "Somebody Else"),
                    stubAlbum("al-2", "Greatest Hits", artistName = "ABBA"),
                ),
            )
            override suspend fun album(id: String) =
                AlbumDetail(stubAlbum(id, "Greatest Hits"), listOf(stubTrack("tr-$id")))
        }
        val queue = resolver(repo).queue(
            Request(query = "greatest hits by abba", focus = Focus.Album, album = "Greatest Hits", artist = "ABBA"),
        )
        assertEquals(listOf("tr-al-2"), queue?.tracks?.map { it.id })
        assertEquals(MediaId.Album("al-2"), queue?.container)
    }

    @Test
    fun `playlist focus matches by name`() = runTest {
        val tracks = listOf(stubTrack("tr-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun playlists() = listOf(
                Playlist("pl-1", "Road Trip", trackCount = 1, durationSec = 60, artworkUrl = null),
            )
            override suspend fun playlist(id: String) =
                PlaylistDetail(Playlist(id, "Road Trip", 1, 60, null), tracks)
        }
        val queue = resolver(repo).queue(
            Request(query = "road trip", focus = Focus.Playlist, playlist = "Road Trip"),
        )
        assertEquals(tracks, queue?.tracks)
        assertEquals(MediaId.Playlist("pl-1"), queue?.container)
    }

    @Test
    fun `genre focus resolves through the catalog mix container`() = runTest {
        val jazz = listOf(stubTrack("tr-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun genres() = listOf(Genre("Jazz", songCount = 9))
        }
        val queue = resolver(
            repo,
            containerTracks = { container ->
                if (container == MediaId.HomeGenre("Jazz")) jazz else emptyList()
            },
        ).queue(Request(query = "some jazz", focus = Focus.Genre, genre = "jazz"))
        assertEquals(jazz, queue?.tracks)
        assertEquals(MediaId.HomeGenre("Jazz"), queue?.container)
    }

    @Test
    fun `missed focused lookup falls back to the generic search`() = runTest {
        val track = stubTrack("tr-1").copy(title = "Unknown Artist Song")
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) =
                if (query == "Nobody") results() else results(tracks = listOf(track))
        }
        val queue = resolver(repo).queue(
            Request(query = "unknown artist song", focus = Focus.Artist, artist = "Nobody"),
        )
        assertEquals(listOf(track), queue?.tracks)
    }

    @Test
    fun `favourites phrase plays the favourite songs without searching`() = runTest {
        val favourites = listOf(stubTrack("tr-1"))
        val resolver = resolver(
            FakeMusicRepository(),
            containerTracks = { container ->
                if (container == MediaId.HomeFavoriteSongs) favourites else emptyList()
            },
        )
        for (phrase in listOf("my favourites", "Favorites", "my liked songs", "starred tracks")) {
            val queue = resolver.queue(Request(query = phrase))
            assertEquals(favourites, queue?.tracks, phrase)
            assertEquals(MediaId.HomeFavoriteSongs, queue?.container, phrase)
        }
    }

    @Test
    fun `a playlist named like the favourites phrase still wins under playlist focus`() = runTest {
        val tracks = listOf(stubTrack("tr-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun playlists() =
                listOf(Playlist("pl-1", "My Favourites", trackCount = 1, durationSec = 60, artworkUrl = null))
            override suspend fun playlist(id: String) =
                PlaylistDetail(Playlist(id, "My Favourites", 1, 60, null), tracks)
        }
        val queue = resolver(repo).queue(
            Request(query = "my favourites", focus = Focus.Playlist, playlist = "my favourites"),
        )
        assertEquals(MediaId.Playlist("pl-1"), queue?.container)
    }

    @Test
    fun `exact station name plays the station without searching`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() =
                listOf(stubStation("rs-1", "Radio Paradise"), stubStation("rs-2", "BBC World Service"))
        }
        assertEquals(Station("rs-2"), resolver(repo).resolve(Request(query = "bbc world service")))
        assertEquals(Station("rs-1"), resolver(repo).resolve(Request(query = "paradise radio")))
    }

    @Test
    fun `partial station name beats the fuzzy library fallback but not an exact hit`() = runTest {
        val sessions = stubTrack("tr-1").copy(title = "BBC Sessions")
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() = listOf(stubStation("rs-1", "BBC World Service"))
            override suspend fun search(query: String) = results(tracks = listOf(sessions))
        }
        assertEquals(Station("rs-1"), resolver(repo).resolve(Request(query = "bbc")))
        assertEquals(listOf(sessions), resolver(repo).queue(Request(query = "bbc sessions"))?.tracks)
    }

    @Test
    fun `radio focus resolves the channel name to a station`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() = listOf(stubStation("rs-1", "Radio Paradise"))
        }
        val resolved = resolver(repo).resolve(
            Request(query = "paradise radio", focus = Focus.Radio, radioChannel = "Paradise"),
        )
        assertEquals(Station("rs-1"), resolved)
    }

    @Test
    fun `radio focus without a station falls back to the library`() = runTest {
        val best = listOf(stubTrack("tr-9", artistId = "ar-1"))
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) =
                results(artists = listOf(stubArtist("ar-1", "Kent")))
        }
        val queue = resolver(
            repo,
            containerTracks = { container ->
                if (container == MediaId.HomeBestOf("ar-1")) best else emptyList()
            },
        ).queue(Request(query = "kent radio", focus = Focus.Radio, radioChannel = "Kent"))
        assertEquals(best, queue?.tracks)
    }

    @Test
    fun `a bare radio plays the first station`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun radioStations() =
                listOf(stubStation("rs-1", "First"), stubStation("rs-2", "Second"))
        }
        assertEquals(Station("rs-1"), resolver(repo).resolve(Request(query = "the radio")))
    }

    @Test
    fun `nothing matching resolves to null`() = runTest {
        val repo = object : FakeMusicRepository() {
            override suspend fun search(query: String) = results()
        }
        assertNull(resolver(repo).queue(Request(query = "zzz")))
    }

    @Test
    fun `station key drops a leading or trailing radio, never a prefix of a name`() {
        assertEquals("paradise", VoiceSearchResolver.stationKey("Radio Paradise"))
        assertEquals("paradise", VoiceSearchResolver.stationKey("paradise radio"))
        assertEquals("radiohead", VoiceSearchResolver.stationKey("Radiohead"))
        assertEquals("", VoiceSearchResolver.stationKey("the radio"))
    }

    @Test
    fun `voice key tolerates case, whitespace and a leading the`() {
        assertEquals(
            VoiceSearchResolver.voiceKey("the  Beatles "),
            VoiceSearchResolver.voiceKey("Beatles"),
        )
    }
}
