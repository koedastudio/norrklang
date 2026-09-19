package studio.koeda.norrklang.subsonic

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SubsonicClientTest {

    private val credentials =
        SubsonicCredentials.fromInput("https://music.example.com", "demo", "secret")

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        SubsonicClient(
            credentials,
            MockEngine { request ->
                lastRequestUrl = request.url.toString()
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    private var lastRequestUrl: String = ""
    private val lastRequestParams get() = Url(lastRequestUrl).parameters

    private fun lastRequestParams(name: String): List<String> =
        Url(lastRequestUrl).parameters.getAll(name).orEmpty()

    @Test
    fun `ping succeeds on ok envelope`() = runTest {
        clientReturning("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").ping()
        assertTrue("/rest/ping.view" in lastRequestUrl)
        assertTrue("u=demo" in lastRequestUrl)
        assertTrue("f=json" in lastRequestUrl)
        assertTrue("t=" in lastRequestUrl)
        assertTrue("s=" in lastRequestUrl)
    }

    @Test
    fun `malformed base url maps to NetworkError, not a raw Ktor exception`() = runTest {
        val garbage = SubsonicCredentials(
            baseUrl = "https://exa mple com:not-a-port",
            username = "demo",
            auth = SubsonicTokenAuth(salt = "abc123", token = "d41d8cd98f00b204e9800998ecf8427e"),
        )
        val client = SubsonicClient(garbage, MockEngine { respond("unreached") })
        assertFailsWith<SubsonicException.NetworkError> { client.ping() }
    }

    @Test
    fun `credential toString never contains the token`() {
        val token = (credentials.auth as SubsonicTokenAuth).token
        assertTrue(token !in credentials.toString())
        assertTrue(token !in credentials.auth.toString())
    }

    @Test
    fun `wrong credentials map to AuthFailed`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        val e = assertFailsWith<SubsonicException.AuthFailed> { client.ping() }
        assertEquals("Wrong username or password", e.message)
    }

    @Test
    fun `missing item maps to NotFound`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"failed",
                "error":{"code":70,"message":"Album not found"}}}""",
        )
        assertFailsWith<SubsonicException.NotFound> { client.getAlbum("nope") }
    }

    @Test
    fun `http error maps to ServerError`() = runTest {
        val client = clientReturning("oops", HttpStatusCode.BadGateway)
        assertFailsWith<SubsonicException.ServerError> { client.ping() }
    }

    @Test
    fun `non-subsonic body maps to ServerError`() = runTest {
        val client = clientReturning("""<html>not an api</html>""")
        assertFailsWith<SubsonicException.ServerError> { client.ping() }
    }

    @Test
    fun `getArtists preserves the alphabetical index`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","artists":{"index":[
                 {"name":"A","artist":[{"id":"ar-1","name":"ABBA","albumCount":9}]},
                 {"name":"B","artist":[{"id":"ar-2","name":"Bo Kaspers","albumCount":12},
                                        {"id":"ar-3","name":"Broder Daniel"}]}
               ]}}}""",
        )
        val index = client.getArtists()
        assertEquals(listOf("A", "B"), index.map { it.name })
        assertEquals(listOf("ABBA"), index[0].artist.map { it.name })
        assertEquals(listOf("Bo Kaspers", "Broder Daniel"), index[1].artist.map { it.name })
        assertEquals(9, index[0].artist.first().albumCount)
    }

    @Test
    fun `getRandomSongs parses the song list and sends size`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","randomSongs":{"song":[
                 {"id":"tr-1","title":"Changes","albumId":"al-1"},
                 {"id":"tr-2","title":"Sound and Vision","albumId":"al-2"}
               ]}}}""",
        )
        val songs = client.getRandomSongs(size = 25)
        assertTrue("/rest/getRandomSongs.view" in lastRequestUrl)
        assertTrue("size=25" in lastRequestUrl)
        assertEquals(listOf("Changes", "Sound and Vision"), songs.map { it.title })
    }

    @Test
    fun `getRandomSongs defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getRandomSongs())
    }

    @Test
    fun `getRandomSongs sends genre and year filters only when given`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        client.getRandomSongs(size = 50, genre = "Hard Rock")
        assertTrue("genre=Hard+Rock" in lastRequestUrl || "genre=Hard%20Rock" in lastRequestUrl)
        assertTrue("fromYear" !in lastRequestUrl && "toYear" !in lastRequestUrl)

        client.getRandomSongs(size = 50, fromYear = 1980, toYear = 1989)
        assertTrue("fromYear=1980" in lastRequestUrl)
        assertTrue("toYear=1989" in lastRequestUrl)
        assertTrue("genre" !in lastRequestUrl)
    }

    @Test
    fun `getGenres parses names and counts`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","genres":{"genre":[
                 {"songCount":1186,"albumCount":115,"value":"Rock"},
                 {"songCount":30,"albumCount":4,"value":"Jazz"}
               ]}}}""",
        )
        val genres = client.getGenres()
        assertTrue("/rest/getGenres.view" in lastRequestUrl)
        assertEquals(listOf("Rock", "Jazz"), genres.map { it.value })
        assertEquals(1186, genres[0].songCount)
        assertEquals(115, genres[0].albumCount)
    }

    @Test
    fun `getGenres defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getGenres())
    }

    @Test
    fun `getAlbumList2ByYear requests the byYear flavor with the range`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[
                 {"id":"al-1","name":"Scary Monsters","year":1980}
               ]}}}""",
        )
        val albums = client.getAlbumList2ByYear(fromYear = 1980, toYear = 1989, size = 10)
        assertTrue("type=byYear" in lastRequestUrl)
        assertTrue("fromYear=1980" in lastRequestUrl)
        assertTrue("toYear=1989" in lastRequestUrl)
        assertTrue("size=10" in lastRequestUrl)
        assertEquals(listOf("Scary Monsters"), albums.map { it.name })
    }

    @Test
    fun `getAlbumList2ByGenre requests the byGenre flavor with the name`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[
                 {"id":"al-1","name":"Aladdin Sane","genre":"Glam Rock"}
               ]}}}""",
        )
        val albums = client.getAlbumList2ByGenre("Glam Rock", size = 10)
        assertTrue("type=byGenre" in lastRequestUrl)
        assertTrue("genre=Glam+Rock" in lastRequestUrl || "genre=Glam%20Rock" in lastRequestUrl)
        assertTrue("size=10" in lastRequestUrl)
        assertEquals(listOf("Aladdin Sane"), albums.map { it.name })
    }

    @Test
    fun `getArtistInfo2 parses similar artists and sends id and count`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","artistInfo2":{
                 "biography":"Some text",
                 "similarArtist":[
                   {"id":"ar-2","name":"Iggy Pop","albumCount":7},
                   {"id":"-1","name":"Not In Library"}
                 ]}}}""",
        )
        val info = client.getArtistInfo2("ar-1", count = 10)
        assertTrue("/rest/getArtistInfo2.view" in lastRequestUrl)
        assertTrue("id=ar-1" in lastRequestUrl)
        assertTrue("count=10" in lastRequestUrl)
        assertEquals(listOf("Iggy Pop", "Not In Library"), info.similarArtist.map { it.name })
        // albumCount is the "is it in the library" signal — must parse and default to 0.
        assertEquals(7, info.similarArtist[0].albumCount)
        assertEquals(0, info.similarArtist[1].albumCount)
    }

    @Test
    fun `getArtistInfo2 defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getArtistInfo2("ar-1").similarArtist)
    }

    @Test
    fun `getSimilarSongs2 parses the song list and sends id and count`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","similarSongs2":{"song":[
                 {"id":"tr-1","title":"Lust for Life","artistId":"ar-2"},
                 {"id":"tr-2","title":"Heroes","artistId":"ar-1"}
               ]}}}""",
        )
        val songs = client.getSimilarSongs2("ar-1", count = 40)
        assertTrue("/rest/getSimilarSongs2.view" in lastRequestUrl)
        assertTrue("id=ar-1" in lastRequestUrl)
        assertTrue("count=40" in lastRequestUrl)
        assertEquals(listOf("Lust for Life", "Heroes"), songs.map { it.title })
    }

    @Test
    fun `getSimilarSongs2 defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getSimilarSongs2("ar-1"))
    }

    @Test
    fun `getTopSongs sends the artist name not an id`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","topSongs":{"song":[
                 {"id":"tr-1","title":"Life on Mars?"}
               ]}}}""",
        )
        val songs = client.getTopSongs("David Bowie", count = 5)
        assertTrue("/rest/getTopSongs.view" in lastRequestUrl)
        assertTrue("artist=David+Bowie" in lastRequestUrl || "artist=David%20Bowie" in lastRequestUrl)
        assertTrue("count=5" in lastRequestUrl)
        assertEquals(listOf("Life on Mars?"), songs.map { it.title })
    }

    @Test
    fun `getTopSongs defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getTopSongs("David Bowie"))
    }

    @Test
    fun `getAlbum returns songs in order`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","album":{
                 "id":"al-1","name":"Hunky Dory","artist":"David Bowie","artistId":"ar-9",
                 "coverArt":"al-1","songCount":2,"duration":500,"year":1971,
                 "song":[
                   {"id":"tr-1","title":"Changes","track":1,"duration":217,"albumId":"al-1"},
                   {"id":"tr-2","title":"Oh! You Pretty Things","track":2,"duration":192,"albumId":"al-1"}
                 ]}}}""",
        )
        val album = client.getAlbum("al-1")
        assertEquals("Hunky Dory", album.name)
        assertEquals(listOf("Changes", "Oh! You Pretty Things"), album.song.map { it.title })
    }

    @Test
    fun `getAlbumList2 starred requests the starred flavor`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[
                 {"id":"al-1","name":"Hunky Dory","artist":"David Bowie","songCount":11}
               ]}}}""",
        )
        val albums = client.getAlbumList2(SubsonicClient.AlbumListType.STARRED, size = 10)
        assertTrue("/rest/getAlbumList2.view" in lastRequestUrl)
        assertTrue("type=starred" in lastRequestUrl)
        assertEquals(listOf("Hunky Dory"), albums.map { it.name })
    }

    @Test
    fun `getPlaylists parses playlist collection`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","playlists":{"playlist":[
                 {"id":"pl-1","name":"Roadtrip","songCount":42,"duration":9000},
                 {"id":"pl-2","name":"Focus","songCount":13,"duration":3000}
               ]}}}""",
        )
        assertEquals(listOf("Roadtrip", "Focus"), client.getPlaylists().map { it.name })
    }

    @Test
    fun `scrobble sends id and submission`() = runTest {
        clientReturning("""{"subsonic-response":{"status":"ok"}}""")
            .scrobble("tr-9", submission = true)
        assertTrue("/rest/scrobble.view" in lastRequestUrl)
        assertTrue("id=tr-9" in lastRequestUrl)
        assertTrue("submission=true" in lastRequestUrl)
    }

    @Test
    fun `getStarred2 parses starred songs and defaults to empty`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","starred2":{"song":[
                 {"id":"tr-1","title":"Changes"},
                 {"id":"tr-2","title":"Life on Mars?"}
               ]}}}""",
        )
        assertEquals(listOf("Changes", "Life on Mars?"), client.getStarred2().song.map { it.title })

        val empty = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), empty.getStarred2().song)
    }

    @Test
    fun `star and unstar send the id`() = runTest {
        clientReturning("""{"subsonic-response":{"status":"ok"}}""").star("tr-9")
        assertTrue("/rest/star.view" in lastRequestUrl)
        assertTrue("id=tr-9" in lastRequestUrl)

        clientReturning("""{"subsonic-response":{"status":"ok"}}""").unstar("tr-9")
        assertTrue("/rest/unstar.view" in lastRequestUrl)
        assertTrue("id=tr-9" in lastRequestUrl)
    }

    @Test
    fun `starAlbum and unstarAlbum send the albumId parameter`() = runTest {
        clientReturning("""{"subsonic-response":{"status":"ok"}}""").starAlbum("al-7")
        assertTrue("/rest/star.view" in lastRequestUrl)
        assertTrue("albumId=al-7" in lastRequestUrl)

        clientReturning("""{"subsonic-response":{"status":"ok"}}""").unstarAlbum("al-7")
        assertTrue("/rest/unstar.view" in lastRequestUrl)
        assertTrue("albumId=al-7" in lastRequestUrl)
    }

    @Test
    fun `album starred state parses and defaults to null`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[
                 {"id":"al-1","name":"Hunky Dory","starred":"2026-07-01T18:04:00Z"},
                 {"id":"al-2","name":"Low"}
               ]}}}""",
        )
        val albums = client.getAlbumList2(SubsonicClient.AlbumListType.ALPHABETICAL)
        assertEquals("2026-07-01T18:04:00Z", albums[0].starred)
        assertEquals(null, albums[1].starred)
    }

    @Test
    fun `getMusicFolders parses id and name`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"ok","musicFolders":{"musicFolder":[
                 {"id":1,"name":"Music"},{"id":3,"name":"Kids"}
               ]}}}""",
        )
        val folders = client.getMusicFolders()
        assertTrue("/rest/getMusicFolders.view" in lastRequestUrl)
        assertEquals(listOf(1 to "Music", 3 to "Kids"), folders.map { it.id to it.name })
    }

    @Test
    fun `getMusicFolders defaults to empty on missing payload`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        assertEquals(emptyList(), client.getMusicFolders())
    }

    @Test
    fun `library-scoped endpoints repeat musicFolderId once per id`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")
        val ids = listOf("1", "3")

        client.getArtists(ids)
        assertTrue("/rest/getArtists.view" in lastRequestUrl)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.getAlbumList2(SubsonicClient.AlbumListType.NEWEST, size = 5, musicFolderIds = ids)
        assertTrue("/rest/getAlbumList2.view" in lastRequestUrl)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.getAlbumList2ByYear(1980, 1989, musicFolderIds = ids)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.getAlbumList2ByGenre("Jazz", musicFolderIds = ids)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.getStarred2(ids)
        assertTrue("/rest/getStarred2.view" in lastRequestUrl)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.getRandomSongs(size = 10, musicFolderIds = ids)
        assertTrue("/rest/getRandomSongs.view" in lastRequestUrl)
        assertEquals(ids, lastRequestParams("musicFolderId"))

        client.search3("bowie", musicFolderIds = ids)
        assertTrue("/rest/search3.view" in lastRequestUrl)
        assertEquals(ids, lastRequestParams("musicFolderId"))
    }

    @Test
    fun `an empty musicFolderIds list sends no musicFolderId at all`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")

        client.getArtists()
        assertTrue("musicFolderId" !in lastRequestUrl)
        client.getAlbumList2(SubsonicClient.AlbumListType.NEWEST)
        assertTrue("musicFolderId" !in lastRequestUrl)
        client.getStarred2()
        assertTrue("musicFolderId" !in lastRequestUrl)
        client.getRandomSongs()
        assertTrue("musicFolderId" !in lastRequestUrl)
        client.search3("bowie")
        assertTrue("musicFolderId" !in lastRequestUrl)
    }

    @Test
    fun `search3 sends the per-type counts`() = runTest {
        val client = clientReturning("""{"subsonic-response":{"status":"ok"}}""")

        client.search3("Hunky Dory", artistCount = 0, albumCount = 100, songCount = 0)

        assertEquals(listOf("0"), lastRequestParams("artistCount"))
        assertEquals(listOf("100"), lastRequestParams("albumCount"))
        assertEquals(listOf("0"), lastRequestParams("songCount"))
        assertEquals(listOf("Hunky Dory"), lastRequestParams("query"))
    }

    @Test
    fun `wrong credentials are not flagged for password fallback`() = runTest {
        val client = clientReturning(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        val e = assertFailsWith<SubsonicException.AuthFailed> { client.ping() }
        assertEquals(40, e.code)
        assertFalse(e.isTokenAuthUnsupported)
    }

    @Test
    fun `token auth rejection maps to AuthFailed flagged for password fallback`() = runTest {
        for (code in listOf(41, 42)) {
            val client = clientReturning(
                """{"subsonic-response":{"status":"failed","version":"1.16.1",
                    "error":{"code":$code,"message":"Token-based authentication not supported"}}}""",
            )
            val e = assertFailsWith<SubsonicException.AuthFailed> { client.ping() }
            assertEquals(code, e.code)
            assertTrue(e.isTokenAuthUnsupported)
        }
    }

    @Test
    fun `password auth sends the encoded password and no token pair`() = runTest {
        val client = SubsonicClient(
            credentials.withPasswordAuth("secret"),
            MockEngine { request ->
                lastRequestUrl = request.url.toString()
                respond(
                    content = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        client.ping()
        assertEquals("enc:736563726574", lastRequestParams["p"])
        assertEquals("demo", lastRequestParams["u"])
        assertNull(lastRequestParams["t"])
        assertNull(lastRequestParams["s"])
    }
}
