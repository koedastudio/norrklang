package studio.koeda.norrklang.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.BrowseList

/** Every scoped browse node: both tabs and every tile but Radio (stations belong to the server). */
internal fun libraryScopeNodes(): List<MediaId> =
    listOf(MediaId.Root, MediaId.TabHome, MediaId.TabLibrary) +
        HomeTile.entries.filter { it.section != HomeTile.Section.RADIO }.map { it.mediaId }

/**
 * The browse nodes showing [list], to refresh once its background reload
 * differed. [artistBuckets] are the artist letter folders a host has opened.
 */
internal fun changedNodes(
    list: BrowseList,
    artistBuckets: List<MediaId> = emptyList(),
): List<MediaId> = when (list) {
    // The scope every other list is filtered by may have moved.
    BrowseList.LIBRARIES -> libraryScopeNodes()
    // Home's top stations and Library's Radio section come from the same list.
    BrowseList.RADIO_STATIONS -> listOf(MediaId.TabHome, MediaId.TabLibrary, MediaId.TabRadio)
    BrowseList.PLAYLISTS -> listOf(MediaId.TabPlaylists)
    BrowseList.ARTISTS -> listOf(MediaId.TabArtists) + artistBuckets
    BrowseList.RECENTLY_ADDED_ALBUMS -> listOf(MediaId.HomeRecentlyAdded)
    BrowseList.RECENTLY_PLAYED_ALBUMS -> listOf(MediaId.HomeRecentlyPlayed)
    BrowseList.FAVORITE_ALBUMS -> listOf(MediaId.HomeFavoriteAlbums)
    BrowseList.FAVORITE_ARTISTS -> listOf(MediaId.HomeFavoriteArtists)
    BrowseList.FAVORITE_TRACKS -> listOf(MediaId.HomeFavoriteSongs)
    // Only the playback heart reads these; PlaybackButtonsListener follows them itself.
    BrowseList.FAVORITE_TRACK_IDS -> emptyList()
    BrowseList.RECENTLY_ADDED_TRACKS -> listOf(MediaId.HomeRecentlyAddedSongs)
}

/**
 * Gathers node refreshes arriving within [windowMs] of each other into one
 * pass, each node once: several lists finish reloading together after a
 * cold start, and a host re-fetching a node per signal would flicker.
 * Confined to [scope]'s thread.
 */
internal class CoalescedNotifier(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val notify: (MediaId) -> Unit,
) {
    private val waiting = LinkedHashSet<MediaId>()
    private var flush: Job? = null

    fun post(nodes: Collection<MediaId>) {
        waiting += nodes
        flush?.cancel()
        flush = scope.launch {
            delay(windowMs)
            val batch = waiting.toList()
            waiting.clear()
            batch.forEach(notify)
        }
    }
}
