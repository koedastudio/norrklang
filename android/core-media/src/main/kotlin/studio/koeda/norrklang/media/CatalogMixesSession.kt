package studio.koeda.norrklang.media

import java.util.Calendar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The "Genre mixes" and "Decade mixes" home sections: tiles derived from the
 * library catalog (as opposed to the listening-history-driven "Made for you"
 * section, see [ArtistMixesSession]).
 *
 * Tiles: the [MAX_GENRES] biggest genres with at least [MIN_GENRE_SONGS]
 * songs, and every decade from [FIRST_DECADE] to today holding at least
 * [MIN_DECADE_ALBUMS] albums (oldest first). Each tile opens a mix of
 * [MIX_SIZE] random library tracks matching the genre or decade, built on
 * first browse.
 *
 * Snapshot/stability semantics live in [HomeMixesSession].
 */
internal class CatalogMixesSession(
    repository: MusicRepository,
    private val currentYear: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) },
) : HomeMixesSession<MediaId.CatalogMix, CatalogMixesSession.Snapshot>(repository) {

    data class GenreMix(val name: String, val artworkUrl: String?)

    data class DecadeMix(val startYear: Int, val artworkUrl: String?)

    class Snapshot(val genres: List<GenreMix>, val decades: List<DecadeMix>)

    /** The genre tiles as the home grid should show them; never generates. */
    suspend fun currentGenreMixes(): List<GenreMix> = currentSnapshot()?.genres.orEmpty()

    /** The decade tiles as the home grid should show them; never generates. */
    suspend fun currentDecadeMixes(): List<DecadeMix> = currentSnapshot()?.decades.orEmpty()

    override fun isEmpty(snapshot: Snapshot): Boolean =
        snapshot.genres.isEmpty() && snapshot.decades.isEmpty()

    /** Tile track lists are built lazily on first browse, not at refresh. */
    override fun sectionTracks(snapshot: Snapshot): Map<MediaId.CatalogMix, List<Track>> =
        emptyMap()

    override suspend fun buildTracksFor(key: MediaId.CatalogMix): List<Track> = when (key) {
        is MediaId.HomeGenre ->
            repository.randomTracksByGenre(key.name, MIX_SIZE)
        is MediaId.HomeDecade ->
            repository.randomTracksByYearRange(key.startYear, key.startYear + 9, MIX_SIZE)
    }

    override suspend fun generate(): Snapshot = coroutineScope {
        val genres = async { genreMixes() }
        val decades = async { decadeMixes() }
        Snapshot(genres.await(), decades.await())
    }

    /** The biggest genres, artwork from the first covered album in each. */
    private suspend fun genreMixes(): List<GenreMix> {
        val top = repository.genres()
            .filter { it.songCount >= MIN_GENRE_SONGS }
            .sortedByDescending { it.songCount }
            .take(MAX_GENRES)
        return coroutineScope {
            top.map { genre ->
                async {
                    val artwork = repository.albumsByGenre(genre.name, ARTWORK_CANDIDATES)
                        .firstNotNullOfOrNull { it.artworkUrl }
                    GenreMix(genre.name, artwork)
                }
            }.awaitAll()
        }
    }

    /**
     * Every decade with enough albums, oldest first; the album-count answer
     * doubles as the artwork source.
     */
    private suspend fun decadeMixes(): List<DecadeMix> = coroutineScope {
        (FIRST_DECADE..currentYear() step 10).map { startYear ->
            async {
                val albums =
                    repository.albumsByYearRange(startYear, startYear + 9, ARTWORK_CANDIDATES)
                if (albums.size >= MIN_DECADE_ALBUMS) {
                    DecadeMix(startYear, albums.firstNotNullOfOrNull { it.artworkUrl })
                } else {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        const val MAX_GENRES = 6
        const val MIX_SIZE = 50

        /**
         * A genre needs at least this many songs before a [MIX_SIZE]-track
         * mix stops repeating itself into noise.
         */
        const val MIN_GENRE_SONGS = 20

        /** A decade with fewer albums is the whole discography, not a mix. */
        const val MIN_DECADE_ALBUMS = 3

        /** Decades before this are folded away — car libraries rarely reach them. */
        const val FIRST_DECADE = 1950

        /** Albums fetched per tile: enough to find a cover and count content. */
        const val ARTWORK_CANDIDATES = 10
    }
}
