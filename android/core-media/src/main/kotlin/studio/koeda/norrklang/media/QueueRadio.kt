package studio.koeda.norrklang.media

import kotlin.random.Random
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Builds the batch of tracks appended when a play queue nears its end (see
 * [QueueRadioListener]). Both recipes return empty when the server has
 * nothing new to offer — the caller's "this queue is done growing" signal.
 */
internal class QueueRadio(
    private val repository: MusicRepository,
    private val random: Random = Random.Default,
) {

    /**
     * Up to [APPEND_COUNT] library tracks similar to [seedArtistId], skipping
     * [excludeTrackIds], capped per artist and interleaved so no artist
     * clumps; empty when the server has no similarity data.
     */
    suspend fun similarContinuation(
        seedArtistId: String,
        excludeTrackIds: Set<String>,
    ): List<Track> {
        val buckets = LinkedHashMap<String, MutableList<Track>>()
        val seen = HashSet<String>()
        for (track in repository.similarTracks(seedArtistId, FETCH_COUNT)) {
            if (track.id in excludeTrackIds || !seen.add(track.id)) continue
            val key = artistKey(track) ?: continue
            val bucket = buckets.getOrPut(key) { mutableListOf() }
            if (bucket.size < PER_ARTIST_CAP) bucket += track
        }
        return interleave(buckets, limit = APPEND_COUNT, random = random)
    }

    /** Up to [APPEND_COUNT] random library tracks, skipping [excludeTrackIds]. */
    suspend fun randomContinuation(excludeTrackIds: Set<String>): List<Track> =
        repository.randomTracks(FETCH_COUNT)
            .filterNot { it.id in excludeTrackIds }
            .distinctBy { it.id }
            .take(APPEND_COUNT)

    companion object {
        /** Oversized fetch so the batch survives dedup against the queue. */
        const val FETCH_COUNT = 50
        const val APPEND_COUNT = 20
        const val PER_ARTIST_CAP = 5
    }
}
