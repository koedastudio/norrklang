package studio.koeda.norrklang.media

import kotlin.random.Random
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Track

/**
 * Orders bucketed tracks so no artist clumps: draw from a largest bucket
 * whose artist differs from the last pick (random among ties). Greedy
 * avoids adjacent repeats whenever the largest bucket holds at most half
 * the remainder — guaranteed at the start by a per-artist cap. Stops at
 * [limit]. Shared by [SimilarMixesSession] and [QueueRadio].
 */
internal fun interleave(
    buckets: Map<String, List<Track>>,
    limit: Int,
    random: Random,
): List<Track> {
    val remaining = buckets.entries.associateTo(LinkedHashMap()) { (key, tracks) ->
        key to ArrayDeque(tracks.shuffled(random))
    }
    val out = ArrayList<Track>(minOf(limit, buckets.values.sumOf { it.size }))
    var lastKey: String? = null
    val ties = ArrayList<String>()
    while (out.size < limit && remaining.isNotEmpty()) {
        var largest = -1
        ties.clear()
        for ((key, tracks) in remaining) {
            if (key == lastKey) continue
            if (tracks.size > largest) {
                largest = tracks.size
                ties.clear()
                ties += key
            } else if (tracks.size == largest) {
                ties += key
            }
        }
        // Only the last-picked artist's bucket left — a clump is unavoidable.
        val pick = if (ties.isEmpty()) lastKey!! else ties.random(random)
        val bucket = remaining.getValue(pick)
        out += bucket.removeFirst()
        if (bucket.isEmpty()) remaining.remove(pick)
        lastKey = pick
    }
    return out
}

/**
 * Grouping key for the per-artist cap and the interleaver. Keyed by displayed
 * artist name, not id: collaboration-album tracks can carry a different (or
 * no) artist id under the same visible name, and separate buckets would let
 * one visible artist exceed the cap. The id is a fallback for nameless tracks.
 */
internal fun artistKey(track: Track): String? =
    track.artistName?.lowercase() ?: track.artistId

/** Same rule for a queried [Artist], whose name is always present. */
internal fun artistKey(artist: Artist): String = artist.name.lowercase()
