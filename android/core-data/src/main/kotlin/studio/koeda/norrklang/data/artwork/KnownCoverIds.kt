package studio.koeda.norrklang.data.artwork

import java.util.concurrent.ConcurrentHashMap

/**
 * Cover-art ids this process has handed out as artwork URIs (recorded by
 * [ArtworkContract.coverUri]).
 *
 * The artwork ContentProvider is exported (car media hosts run in other
 * processes), so any app can probe it with arbitrary ids. The provider only
 * *network*-fetches ids registered here — a foreign app can't turn the app
 * into an authenticated fetch proxy or grow the cache unboundedly. Cached
 * files are still served without a registry hit, so hosts keeping URIs across
 * our process restart keep working.
 */
object KnownCoverIds {

    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun register(id: String) {
        ids.add(id)
    }

    operator fun contains(id: String): Boolean = id in ids
}
