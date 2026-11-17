package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.repo.BrowseList

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListChangeNotificationsTest {

    @Test
    fun `stations refresh both tabs that show them`() {
        assertEquals(
            listOf(MediaId.TabHome, MediaId.TabLibrary, MediaId.TabRadio),
            changedNodes(BrowseList.RADIO_STATIONS),
        )
    }

    @Test
    fun `an artist reload also refreshes the letter folders a host opened`() {
        val opened = listOf(MediaId.ArtistBucket("s"), MediaId.ArtistBucket("sa-sk"))

        assertEquals(listOf(MediaId.TabArtists) + opened, changedNodes(BrowseList.ARTISTS, opened))
        assertEquals(listOf(MediaId.TabPlaylists), changedNodes(BrowseList.PLAYLISTS, opened))
    }

    @Test
    fun `every list a browse node shows maps to one, and the heart ids to none`() {
        (BrowseList.entries - BrowseList.FAVORITE_TRACK_IDS)
            .forEach { assertTrue(changedNodes(it).isNotEmpty(), it.name) }
        assertEquals(emptyList(), changedNodes(BrowseList.FAVORITE_TRACK_IDS))
    }

    @Test
    fun `a library change refreshes the scoped nodes but not the radio tile`() {
        val nodes = changedNodes(BrowseList.LIBRARIES)

        assertTrue(MediaId.TabHome in nodes && MediaId.HomeFavoriteSongs in nodes)
        assertTrue(MediaId.TabRadio !in nodes)
    }

    @Test
    fun `signals inside the window become one pass, each node once`() = runTest {
        val notified = mutableListOf<MediaId>()
        val notifier = CoalescedNotifier(backgroundScope, 500) { notified += it }

        notifier.post(listOf(MediaId.TabHome))
        advanceTimeBy(300)
        notifier.post(listOf(MediaId.TabHome, MediaId.TabPlaylists))
        advanceTimeBy(300)
        assertEquals(emptyList(), notified)

        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(MediaId.TabHome, MediaId.TabPlaylists), notified)

        notifier.post(listOf(MediaId.TabHome))
        advanceTimeBy(600)
        runCurrent()
        assertEquals(3, notified.size)
    }
}
