package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ReleaseType

/**
 * The sections of an artist's browse view, as plain data: the artist's
 * releases split by [ReleaseType] in declaration order, then similar artists.
 *
 * Rules: empty sections are never emitted; releases are split by type only
 * once the artist has [MIN_ALBUMS_TO_SPLIT] of them (a couple of tiles under
 * their own header is noise); and headers are shown only when there is more
 * than one section, so a one-section view stays the plain album grid.
 */
internal object ArtistSections {

    /** Fewer releases than this stay one unsplit "Albums" section. */
    const val MIN_ALBUMS_TO_SPLIT = 6

    /** Similar artists shown under the releases. */
    const val SIMILAR_ARTISTS_SHOWN = 8

    sealed interface Section {
        data class Releases(val type: ReleaseType, val albums: List<Album>) : Section
        data class SimilarArtists(val artists: List<Artist>) : Section
    }

    data class View(val sections: List<Section>) {
        /** Whether the host should show a header above each section. */
        val labeled: Boolean get() = sections.size > 1
    }

    fun build(albums: List<Album>, similarArtists: List<Artist>): View {
        val similar = similarArtists.take(SIMILAR_ARTISTS_SHOWN)
        return View(
            releaseSections(albums) +
                listOfNotNull(Section.SimilarArtists(similar).takeIf { similar.isNotEmpty() }),
        )
    }

    private fun releaseSections(albums: List<Album>): List<Section.Releases> = when {
        albums.isEmpty() -> emptyList()
        albums.size < MIN_ALBUMS_TO_SPLIT -> listOf(Section.Releases(ReleaseType.ALBUM, albums))
        else -> {
            val byType = albums.groupBy { it.releaseType }
            ReleaseType.entries.mapNotNull { type -> byType[type]?.let { Section.Releases(type, it) } }
        }
    }
}
