package studio.koeda.norrklang.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import studio.koeda.norrklang.data.artwork.KnownCoverIds
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.ReleaseType
import studio.koeda.norrklang.data.model.Track

/**
 * The JSON below is what installed versions have on disk. A change that
 * stops it decoding only costs one cold start, but should be deliberate.
 */
class PersistedModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stored lists still decode`() {
        assertEquals(
            listOf(Artist("ar-1", "Bowie", 3, "content://app.artwork/cover/ar-1", sortGroup = "B")),
            json.decodeFromString(
                BrowseListSerializers.artists,
                """[{"id":"ar-1","name":"Bowie","albumCount":3,
                    "artworkUrl":"content://app.artwork/cover/ar-1","sortGroup":"B"}]""",
            ),
        )
        assertEquals(
            listOf(
                Album(
                    "al-1", "Low", "Bowie", "ar-1", 1977, 11, 2300, null,
                    isFavorite = true, releaseType = ReleaseType.SINGLE_OR_EP,
                ),
            ),
            json.decodeFromString(
                BrowseListSerializers.albums,
                """[{"id":"al-1","title":"Low","artistName":"Bowie","artistId":"ar-1","year":1977,
                    "trackCount":11,"durationSec":2300,"artworkUrl":null,"isFavorite":true,
                    "releaseType":"SINGLE_OR_EP"}]""",
            ),
        )
        assertEquals(
            listOf(
                Track(
                    "t-1", "Sound and Vision", "Bowie", "ar-1", "Low", "al-1", 4, 1, 183, null,
                    "norrklang-stream://subsonic?id=t-1", libraryId = "1",
                ),
            ),
            json.decodeFromString(
                BrowseListSerializers.tracks,
                """[{"id":"t-1","title":"Sound and Vision","artistName":"Bowie","artistId":"ar-1",
                    "albumTitle":"Low","albumId":"al-1","trackNumber":4,"discNumber":1,
                    "durationSec":183,"artworkUrl":null,
                    "streamUrl":"norrklang-stream://subsonic?id=t-1","libraryId":"1"}]""",
            ),
        )
        assertEquals(
            listOf(Playlist("p-1", "Drive", 20, 4000, null)),
            json.decodeFromString(
                BrowseListSerializers.playlists,
                """[{"id":"p-1","name":"Drive","trackCount":20,"durationSec":4000,"artworkUrl":null}]""",
            ),
        )
        assertEquals(
            listOf(RadioStation("s-1", "P3", "https://example.com/p3", "https://example.com", null)),
            json.decodeFromString(
                BrowseListSerializers.stations,
                """[{"id":"s-1","name":"P3","streamUrl":"https://example.com/p3",
                    "homePageUrl":"https://example.com"}]""",
            ),
        )
        assertEquals(
            listOf(MusicLibrary("1", "Music")),
            json.decodeFromString(BrowseListSerializers.libraries, """[{"id":"1","name":"Music"}]"""),
        )
    }

    @Test
    fun `decoding registers cover ids, percent-encoded ones included`() {
        json.decodeFromString(
            BrowseListSerializers.playlists,
            """[{"id":"p","name":"n","trackCount":0,"durationSec":0,
                "artworkUrl":"content://app.artwork/cover/%2Flibrary%2Fmetadata%2F9%2Fthumb%2F1"},
               {"id":"q","name":"n","trackCount":0,"durationSec":0,
                "artworkUrl":"content://app.artwork/cover/restored-plain-id"}]""",
        )

        assertTrue("/library/metadata/9/thumb/1" in KnownCoverIds)
        assertTrue("restored-plain-id" in KnownCoverIds)
    }
}
