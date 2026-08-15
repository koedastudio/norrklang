package studio.koeda.norrklang.media

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.repo.MusicException

/**
 * Base for the artist-keyed mixes in the "Made for you" home section
 * ("Best of <artist>" — [BestOfMixesSession], "Similar to <artist>" —
 * [SimilarMixesSession]): tiles are [Mix]es seeded from the played-artist
 * pools, keyed by the seed artist's id.
 *
 * Snapshot/stability semantics live in [HomeMixesSession].
 */
internal abstract class ArtistMixesSession(
    repository: MusicRepository,
) : HomeMixesSession<String, List<ArtistMixesSession.Mix>>(repository) {

    data class Mix(
        val artist: Artist,
        val artworkUrl: String?,
        val tracks: List<Track>,
    )

    /** The section as the home grid should show it; never generates. */
    suspend fun currentMixes(): List<Mix> = currentSnapshot().orEmpty()

    final override fun isEmpty(snapshot: List<Mix>): Boolean = snapshot.isEmpty()

    final override fun sectionTracks(snapshot: List<Mix>): Map<String, List<Track>> =
        snapshot.associate { it.artist.id to it.tracks }

    /** The most-played and recently-played candidate pools, fetched concurrently. */
    protected suspend fun candidatePools(): Pair<List<Artist>, List<Artist>> = coroutineScope {
        val frequent = async { repository.mostPlayedArtists(CANDIDATE_POOL_SIZE) }
        val recent = async { repository.recentlyPlayedArtists(CANDIDATE_POOL_SIZE) }
        frequent.await() to recent.await()
    }

    /** The seed's own cover, else the first track cover the mix can offer. */
    protected suspend fun artworkFor(seed: Artist, tracks: List<Track>): String? {
        seed.artworkUrl?.let { return it }
        val fetched = try {
            repository.artist(seed.id).artist.artworkUrl
        } catch (_: MusicException) {
            null
        }
        return fetched ?: tracks.firstNotNullOfOrNull { it.artworkUrl }
    }

    /**
     * Dead-server bail-out shared by both subclasses: a server without a
     * Last.fm agent answers every probe with nothing, so give up after
     * [DEAD_PROBE_LIMIT] consecutive empties instead of burning car-LTE
     * requests. Only before anything was accepted — once a mix landed,
     * further empties are just gaps in the data.
     */
    protected class DeadProbeTally {
        private var consecutiveDead = 0

        /** Records a candidate with zero Last.fm data; true means give up. */
        fun recordDead(anythingAccepted: Boolean): Boolean {
            consecutiveDead++
            return !anythingAccepted && consecutiveDead >= DEAD_PROBE_LIMIT
        }

        fun recordLive() {
            consecutiveDead = 0
        }
    }

    companion object {
        const val CANDIDATE_POOL_SIZE = 30

        /**
         * Popular tracks fetched per artist; one shared size so both
         * subclasses hit the same repository cache entry.
         */
        const val TOP_SONGS_COUNT = 20

        /** How many consecutive dead probes [DeadProbeTally] tolerates. */
        const val DEAD_PROBE_LIMIT = 3
    }
}
