package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ReleaseType
import studio.koeda.norrklang.media.ArtistSections.Section

class ArtistSectionsTest {

    private fun album(id: String, type: ReleaseType = ReleaseType.ALBUM) = Album(
        id = id,
        title = "Album $id",
        artistName = null,
        artistId = null,
        year = null,
        trackCount = 10,
        durationSec = 2400,
        artworkUrl = null,
        releaseType = type,
    )

    private fun artist(id: String) = Artist(id = id, name = "Artist $id", albumCount = 1, artworkUrl = null)

    private fun albums(count: Int, type: ReleaseType = ReleaseType.ALBUM) =
        List(count) { album("$type-$it", type) }

    @Test
    fun `few releases stay one unlabeled Albums section whatever their types`() {
        val mixed = albums(2) + albums(1, ReleaseType.COMPILATION) + albums(1, ReleaseType.SINGLE_OR_EP)

        val view = ArtistSections.build(mixed, emptyList())

        assertEquals(listOf(Section.Releases(ReleaseType.ALBUM, mixed)), view.sections)
        assertFalse(view.labeled)
    }

    @Test
    fun `enough releases split by type in section order, skipping empty types`() {
        val singles = albums(2, ReleaseType.SINGLE_OR_EP)
        val plain = albums(ArtistSections.MIN_ALBUMS_TO_SPLIT - 2)
        // Interleaved on the way in: grouping must not reorder within a section.
        val input = listOf(singles[0]) + plain + listOf(singles[1])

        val view = ArtistSections.build(input, emptyList())

        assertEquals(
            listOf(
                Section.Releases(ReleaseType.ALBUM, plain),
                Section.Releases(ReleaseType.SINGLE_OR_EP, singles),
            ),
            view.sections,
        )
        assertTrue(view.labeled)
    }

    @Test
    fun `similar artists are a trailing section, capped, and label a single album section`() {
        val plain = albums(2)
        val similar = List(ArtistSections.SIMILAR_ARTISTS_SHOWN + 3) { artist("$it") }

        val view = ArtistSections.build(plain, similar)

        assertEquals(
            listOf(
                Section.Releases(ReleaseType.ALBUM, plain),
                Section.SimilarArtists(similar.take(ArtistSections.SIMILAR_ARTISTS_SHOWN)),
            ),
            view.sections,
        )
        assertTrue(view.labeled)
    }

    @Test
    fun `no releases and no similar artists is an empty view`() {
        val view = ArtistSections.build(emptyList(), emptyList())

        assertEquals(emptyList(), view.sections)
        assertFalse(view.labeled)
    }
}
