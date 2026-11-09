package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import studio.koeda.norrklang.media.MediaId.AlbumSource

class MediaIdTest {

    @Test
    fun `every id round-trips through encode and parse`() {
        val ids = listOf(
            MediaId.Root,
            MediaId.TabHome,
            MediaId.TabLibrary,
            MediaId.TabArtists,
            MediaId.TabAlbums,
            MediaId.TabPlaylists,
            MediaId.TabRadio,
            MediaId.HomeRecentlyAdded,
            MediaId.HomeFavoriteAlbums,
            MediaId.HomeFavoriteArtists,
            MediaId.HomeRecentlyPlayed,
            MediaId.HomeFavoriteSongs,
            MediaId.HomeRecentlyAddedSongs,
            MediaId.HomeRandomMix,
            MediaId.HomeRandomAlbums,
            MediaId.Genres,
            MediaId.Decades,
            MediaId.GenreAlbums("Rock"),
            MediaId.DecadeAlbums(1980),
            MediaId.HomeSimilar("ar-12"),
            MediaId.HomeBestOf("ar-12"),
            MediaId.GenreMix("Rock"),
            MediaId.DecadeMix(1980),
            MediaId.Track("tr-77", MediaId.HomeBestOf("ar-12")),
            MediaId.Track("tr-77", MediaId.GenreMix("Rock")),
            MediaId.Track("tr-77", MediaId.DecadeMix(1980)),
            MediaId.Track("tr-77", MediaId.HomeRandomAlbums),
            MediaId.ArtistBucket("a"),
            MediaId.ArtistBucket("#"),
            MediaId.AlbumBucket(AlbumSource.All, "s:sa:sk"),
            MediaId.AlbumBucket(AlbumSource.Genre("Hard Rock"), "s:sa:sk"),
            MediaId.AlbumBucket(AlbumSource.Decade(1980), "#"),
            MediaId.Artist("ar-12"),
            MediaId.Album("al-9"),
            MediaId.Playlist("pl-3"),
            MediaId.Track("tr-77"),
            MediaId.Track("tr-77", MediaId.Album("al-9")),
            MediaId.Track("tr-77", MediaId.Playlist("pl-3")),
            MediaId.Track("tr-77", MediaId.HomeFavoriteSongs),
            MediaId.Track("tr-77", MediaId.HomeRecentlyAddedSongs),
            MediaId.Track("tr-77", MediaId.HomeRandomMix),
            MediaId.Track("tr-77", MediaId.HomeSimilar("ar-12")),
            MediaId.SongRadio("ar-12"),
            MediaId.Track("tr-77", MediaId.SongRadio("ar-12")),
            MediaId.RadioStation("rs-5"),
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
        // track-context separator and '/' with the type and bucket separators.
        for (name in listOf("Rock/Pop", "Drum & Bass", "Synth | Wave", "Höstvisa 100%")) {
            val mix = MediaId.GenreMix(name)
            assertEquals(mix, MediaId.parse(mix.encode()), "round-trip failed for $name")
            val track = MediaId.Track("tr-1", mix)
            assertEquals(track, MediaId.parse(track.encode()), "context round-trip failed for $name")
            val grid = MediaId.GenreAlbums(name)
            assertEquals(grid, MediaId.parse(grid.encode()), "grid round-trip failed for $name")
            val bucket = MediaId.AlbumBucket(AlbumSource.Genre(name), "s:sa:sk")
            assertEquals(bucket, MediaId.parse(bucket.encode()), "bucket round-trip failed for $name")
        }
    }

    @Test
    fun `malformed genre and decade ids do not parse`() {
        assertNull(MediaId.parse("genre/%zz"))
        assertNull(MediaId.parse("decade/eighties"))
        assertNull(MediaId.parse("genre-albums/%zz"))
        assertNull(MediaId.parse("decade-albums/eighties"))
        assertNull(MediaId.parse("genre-bucket/Rock"))
        assertNull(MediaId.parse("genre-bucket/Rock/"))
        assertNull(MediaId.parse("decade-bucket/eighties/a"))
    }

    @Test
    fun `garbage does not parse`() {
        assertNull(MediaId.parse(""))
        assertNull(MediaId.parse("nonsense"))
        assertNull(MediaId.parse("tab/unknown"))
        assertNull(MediaId.parse("home/unknown"))
        assertNull(MediaId.parse("home/most-played"))
        assertNull(MediaId.parse("artist/"))
        assertNull(MediaId.parse("frog/123"))
        assertNull(MediaId.parse("album-bucket/"))
    }

    @Test
    fun `only containers are valid track contexts`() {
        assertNull(MediaId.parse("track/tr-1|artist/ar-1"))
        assertNull(MediaId.parse("track/tr-1|station/rs-1"))
        assertNull(MediaId.parse("track/tr-1|tab/home"))
        assertNull(MediaId.parse("track/tr-1|home/recently-added"))
        assertNull(MediaId.parse("track/tr-1|home/recently-played"))
        assertNull(MediaId.parse("track/tr-1|library/genres"))
        assertNull(MediaId.parse("track/tr-1|genre-albums/Rock"))
        // Similar mixes are containers, so they ARE a valid context.
        assertEquals(
            MediaId.Track("tr-1", MediaId.HomeSimilar("ar-1")),
            MediaId.parse("track/tr-1|similar/ar-1"),
        )
    }

    @Test
    fun `album sources name their shuffle mix`() {
        assertNull(AlbumSource.All.mix)
        assertEquals(MediaId.GenreMix("Jazz"), AlbumSource.Genre("Jazz").mix)
        assertEquals(MediaId.DecadeMix(1990), AlbumSource.Decade(1990).mix)
    }

    @Test
    fun `encoded forms are stable`() {
        assertEquals("root", MediaId.Root.encode())
        assertEquals("tab/library", MediaId.TabLibrary.encode())
        assertEquals("tab/albums", MediaId.TabAlbums.encode())
        assertEquals("home/recently-added", MediaId.HomeRecentlyAdded.encode())
        assertEquals("home/favorite-albums", MediaId.HomeFavoriteAlbums.encode())
        assertEquals("home/favorite-songs", MediaId.HomeFavoriteSongs.encode())
        assertEquals("home/random-mix", MediaId.HomeRandomMix.encode())
        assertEquals("home/random-albums", MediaId.HomeRandomAlbums.encode())
        assertEquals("library/genres", MediaId.Genres.encode())
        assertEquals("library/decades", MediaId.Decades.encode())
        assertEquals("genre-albums/Hard+Rock", MediaId.GenreAlbums("Hard Rock").encode())
        assertEquals("decade-albums/1980", MediaId.DecadeAlbums(1980).encode())
        assertEquals("album/al-1", MediaId.Album("al-1").encode())
        assertEquals("track/tr-1|album/al-1", MediaId.Track("tr-1", MediaId.Album("al-1")).encode())
        assertEquals(
            "track/tr-1|home/random-mix",
            MediaId.Track("tr-1", MediaId.HomeRandomMix).encode(),
        )
        assertEquals(
            "track/tr-1|home/random-albums",
            MediaId.Track("tr-1", MediaId.HomeRandomAlbums).encode(),
        )
        assertEquals("similar/ar-1", MediaId.HomeSimilar("ar-1").encode())
        assertEquals(
            "track/tr-1|similar/ar-1",
            MediaId.Track("tr-1", MediaId.HomeSimilar("ar-1")).encode(),
        )
        assertEquals("home/recently-played", MediaId.HomeRecentlyPlayed.encode())
        assertEquals("bestof/ar-1", MediaId.HomeBestOf("ar-1").encode())
        assertEquals(
            "track/tr-1|bestof/ar-1",
            MediaId.Track("tr-1", MediaId.HomeBestOf("ar-1")).encode(),
        )
        assertEquals("genre/Hard+Rock", MediaId.GenreMix("Hard Rock").encode())
        assertEquals("decade/1980", MediaId.DecadeMix(1980).encode())
        assertEquals(
            "track/tr-1|decade/1980",
            MediaId.Track("tr-1", MediaId.DecadeMix(1980)).encode(),
        )
        assertEquals("album-bucket/s", MediaId.AlbumBucket(AlbumSource.All, "s").encode())
        assertEquals(
            "genre-bucket/Hard+Rock/s:sa:sk",
            MediaId.AlbumBucket(AlbumSource.Genre("Hard Rock"), "s:sa:sk").encode(),
        )
        assertEquals("decade-bucket/1980/s", MediaId.AlbumBucket(AlbumSource.Decade(1980), "s").encode())
        assertEquals("radio/ar-1", MediaId.SongRadio("ar-1").encode())
        assertEquals("tab/radio", MediaId.TabRadio.encode())
        assertEquals("station/rs-1", MediaId.RadioStation("rs-1").encode())
        assertEquals(
            "track/tr-1|radio/ar-1",
            MediaId.Track("tr-1", MediaId.SongRadio("ar-1")).encode(),
        )
    }
}
