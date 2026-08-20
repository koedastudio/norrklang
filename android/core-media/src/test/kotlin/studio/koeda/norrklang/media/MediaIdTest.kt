package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaIdTest {

    @Test
    fun `every id round-trips through encode and parse`() {
        val ids = listOf(
            MediaId.Root,
            MediaId.TabHome,
            MediaId.TabArtists,
            MediaId.TabAlbums,
            MediaId.TabPlaylists,
            MediaId.HomeRecentlyAdded,
            MediaId.HomeFavoriteAlbums,
            MediaId.HomeRecentlyPlayed,
            MediaId.HomeMostPlayed,
            MediaId.HomeFavoriteSongs,
            MediaId.HomeRandomMix,
            MediaId.HomeSimilar("ar-12"),
            MediaId.HomeBestOf("ar-12"),
            MediaId.HomeGenre("Rock"),
            MediaId.HomeDecade(1980),
            MediaId.Track("tr-77", MediaId.HomeBestOf("ar-12")),
            MediaId.Track("tr-77", MediaId.HomeGenre("Rock")),
            MediaId.Track("tr-77", MediaId.HomeDecade(1980)),
            MediaId.ArtistBucket("a"),
            MediaId.ArtistBucket("#"),
            MediaId.AlbumBucket("s:sa:sk"),
            MediaId.Artist("ar-12"),
            MediaId.Album("al-9"),
            MediaId.Playlist("pl-3"),
            MediaId.Track("tr-77"),
            MediaId.Track("tr-77", MediaId.Album("al-9")),
            MediaId.Track("tr-77", MediaId.Playlist("pl-3")),
            MediaId.Track("tr-77", MediaId.HomeFavoriteSongs),
            MediaId.Track("tr-77", MediaId.HomeRandomMix),
            MediaId.Track("tr-77", MediaId.HomeSimilar("ar-12")),
        )
        for (id in ids) {
            assertEquals(id, MediaId.parse(id.encode()), "round-trip failed for $id")
        }
    }

    @Test
    fun `ids with slashes and dashes survive`() {
        // Navidrome ids are opaque — be defensive about their content.
        val track = MediaId.Track("md5-ab/cd", MediaId.Album("uuid-12-34"))
        assertEquals(track, MediaId.parse(track.encode()))
        val similar = MediaId.HomeSimilar("md5-ab/cd")
        assertEquals(similar, MediaId.parse(similar.encode()))
    }

    @Test
    fun `genre names with special characters survive`() {
        // Genre names are free text; '|' would otherwise collide with the
        // track-context separator and '/' with the type separator.
        for (name in listOf("Rock/Pop", "Drum & Bass", "Synth | Wave", "Höstvisa 100%")) {
            val genre = MediaId.HomeGenre(name)
            assertEquals(genre, MediaId.parse(genre.encode()), "round-trip failed for $name")
            val track = MediaId.Track("tr-1", genre)
            assertEquals(track, MediaId.parse(track.encode()), "context round-trip failed for $name")
        }
    }

    @Test
    fun `malformed genre and decade ids do not parse`() {
        assertNull(MediaId.parse("genre/%zz"))
        assertNull(MediaId.parse("decade/eighties"))
    }

    @Test
    fun `garbage does not parse`() {
        assertNull(MediaId.parse(""))
        assertNull(MediaId.parse("nonsense"))
        assertNull(MediaId.parse("tab/unknown"))
        assertNull(MediaId.parse("home/unknown"))
        assertNull(MediaId.parse("artist/"))
        assertNull(MediaId.parse("frog/123"))
        assertNull(MediaId.parse("album-bucket/"))
    }

    @Test
    fun `only containers are valid track contexts`() {
        assertNull(MediaId.parse("track/tr-1|artist/ar-1"))
        assertNull(MediaId.parse("track/tr-1|tab/home"))
        assertNull(MediaId.parse("track/tr-1|home/recently-added"))
        assertNull(MediaId.parse("track/tr-1|home/recently-played"))
        assertNull(MediaId.parse("track/tr-1|home/most-played"))
        // Similar mixes are containers, so they ARE a valid context.
        assertEquals(
            MediaId.Track("tr-1", MediaId.HomeSimilar("ar-1")),
            MediaId.parse("track/tr-1|similar/ar-1"),
        )
    }

    @Test
    fun `encoded forms are stable`() {
        assertEquals("root", MediaId.Root.encode())
        assertEquals("tab/albums", MediaId.TabAlbums.encode())
        assertEquals("home/recently-added", MediaId.HomeRecentlyAdded.encode())
        assertEquals("home/favorite-albums", MediaId.HomeFavoriteAlbums.encode())
        assertEquals("home/favorite-songs", MediaId.HomeFavoriteSongs.encode())
        assertEquals("home/random-mix", MediaId.HomeRandomMix.encode())
        assertEquals("album/al-1", MediaId.Album("al-1").encode())
        assertEquals("track/tr-1|album/al-1", MediaId.Track("tr-1", MediaId.Album("al-1")).encode())
        assertEquals(
            "track/tr-1|home/random-mix",
            MediaId.Track("tr-1", MediaId.HomeRandomMix).encode(),
        )
        assertEquals("similar/ar-1", MediaId.HomeSimilar("ar-1").encode())
        assertEquals(
            "track/tr-1|similar/ar-1",
            MediaId.Track("tr-1", MediaId.HomeSimilar("ar-1")).encode(),
        )
        assertEquals("home/recently-played", MediaId.HomeRecentlyPlayed.encode())
        assertEquals("home/most-played", MediaId.HomeMostPlayed.encode())
        assertEquals("bestof/ar-1", MediaId.HomeBestOf("ar-1").encode())
        assertEquals(
            "track/tr-1|bestof/ar-1",
            MediaId.Track("tr-1", MediaId.HomeBestOf("ar-1")).encode(),
        )
        assertEquals("genre/Hard+Rock", MediaId.HomeGenre("Hard Rock").encode())
        assertEquals("decade/1980", MediaId.HomeDecade(1980).encode())
        assertEquals(
            "track/tr-1|decade/1980",
            MediaId.Track("tr-1", MediaId.HomeDecade(1980)).encode(),
        )
    }
}
