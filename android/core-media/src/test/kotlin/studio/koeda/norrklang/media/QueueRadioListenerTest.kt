package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * [FakePlayer] with the queue surface [QueueRadioListener] reads and writes.
 * The timeline serves [playOrder] (item indices in play order; natural order
 * by default) through `getNextWindowIndex`, mimicking ExoPlayer's repeat and
 * shuffle semantics.
 */
private class QueuePlayer : FakePlayer() {

    val items = mutableListOf<MediaItem>()
    var index = 0
    var repeat = Player.REPEAT_MODE_OFF
    var shuffle = false
    var playOrder: List<Int>? = null
    var ready = true
    val seeks = mutableListOf<Int>()

    override fun getMediaItemCount() = items.size
    override fun getMediaItemAt(index: Int): MediaItem = items[index]
    override fun getCurrentMediaItemIndex() = index
    override fun getRepeatMode() = repeat
    override fun getShuffleModeEnabled() = shuffle
    override fun getPlayWhenReady() = ready
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        items += mediaItems
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seeks += mediaItemIndex
    }

    override fun getCurrentTimeline(): Timeline = object : Timeline() {
        override fun getWindowCount() = items.size
        override fun getNextWindowIndex(
            windowIndex: Int,
            repeatMode: Int,
            shuffleModeEnabled: Boolean,
        ): Int {
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val order = playOrder ?: items.indices.toList()
            val next = order.indexOf(windowIndex) + 1
            return when {
                next < order.size -> order[next]
                repeatMode == Player.REPEAT_MODE_ALL -> order.first()
                else -> C.INDEX_UNSET
            }
        }
        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window = error("unused")
        override fun getPeriodCount(): Int = error("unused")
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            error("unused")
        override fun getIndexOfPeriod(uid: Any): Int = error("unused")
        override fun getUidOfPeriod(periodIndex: Int): Any = error("unused")
    }
}

class QueueRadioListenerTest {

    /** A queue item as the browse/append paths would mint it, extras-free. */
    private fun item(id: String, container: MediaId.Container? = null): MediaItem =
        MediaItem.Builder().setMediaId(MediaId.Track(id, container).encode()).build()

    private fun stubRepository(
        similar: List<Track> = emptyList(),
        random: List<Track> = emptyList(),
        callDelayMs: Long = 0,
        failure: MusicException? = null,
    ): MusicRepository = object : FakeMusicRepository() {
        var similarCalls = 0
        override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
            if (callDelayMs > 0) delay(callDelayMs)
            failure?.let { throw it }
            similarCalls++
            return similar
        }
        override suspend fun randomTracks(size: Int): List<Track> {
            failure?.let { throw it }
            return random
        }
    }

    /** Artist ids come from a map instead of Bundle extras (Android-free). */
    private fun TestScope.listener(
        player: QueuePlayer,
        repository: MusicRepository,
        enabled: Boolean = true,
        seeds: Map<String, String> = emptyMap(),
    ) = QueueRadioListener(
        scope = backgroundScope,
        autoplayEnabled = { enabled },
        radio = QueueRadio(repository, Random(seed = 3)),
        player = player,
        seedOf = { item -> seeds[item.mediaId] },
        buildItem = { track, container -> item(track.id, container) },
    )

    private fun albumQueue(player: QueuePlayer, size: Int = 3) {
        player.items += (1..size).map { item("tr-$it", MediaId.Album("al-1")) }
    }

    private fun transitionToLast(player: QueuePlayer, listener: QueueRadioListener) {
        player.index = player.items.size - 1
        listener.onMediaItemTransition(
            player.items.last(),
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        )
    }

    @Test
    fun `appends similar tracks when the last item starts`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(similar = (1..30).map { stubTrack("s-$it", "ar-$it") })
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()

        assertTrue(player.items.size > 3, "queue was not extended")
        val appended = player.items.drop(3).map { MediaId.parse(it.mediaId) as MediaId.Track }
        assertTrue(appended.all { it.container == MediaId.SongRadio("ar-seed") })
    }

    @Test
    fun `does not trigger while items remain in play order`() = runTest {
        val player = QueuePlayer()
        albumQueue(player, size = 5)
        val repo = stubRepository(similar = listOf(stubTrack("s-1", "ar-1")))
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        player.index = 1
        listener.onMediaItemTransition(player.items[1], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()

        assertEquals(5, player.items.size)
    }

    @Test
    fun `shuffle triggers on the last item in play order regardless of index`() = runTest {
        val player = QueuePlayer()
        albumQueue(player, size = 4)
        player.shuffle = true
        // Play order ends at item index 0.
        player.playOrder = listOf(2, 3, 1, 0)
        val repo = stubRepository(similar = (1..10).map { stubTrack("s-$it", "ar-$it") })
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        player.index = 0
        listener.onMediaItemTransition(player.items[0], Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()

        assertTrue(player.items.size > 4, "queue was not extended at the shuffle-order tail")
    }

    @Test
    fun `repeat modes disable the radio`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(similar = listOf(stubTrack("s-1", "ar-1")))
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        for (mode in listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)) {
            player.repeat = mode
            transitionToLast(player, listener)
            runCurrent()
            assertEquals(3, player.items.size, "extended under repeat mode $mode")
        }
    }

    @Test
    fun `toggle off is a no-op`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(similar = listOf(stubTrack("s-1", "ar-1")))
        val listener = listener(
            player,
            repo,
            enabled = false,
            seeds = mapOf(player.items.last().mediaId to "ar-seed"),
        )

        transitionToLast(player, listener)
        runCurrent()

        assertEquals(3, player.items.size)
    }

    @Test
    fun `random mix queues extend with random tracks under the same container`() = runTest {
        val player = QueuePlayer()
        player.items += (1..3).map { item("tr-$it", MediaId.HomeRandomMix) }
        val repo = stubRepository(random = (1..30).map { stubTrack("r-$it") })
        val listener = listener(player, repo)

        transitionToLast(player, listener)
        runCurrent()

        assertTrue(player.items.size > 3, "random mix was not extended")
        val appended = player.items.drop(3).map { MediaId.parse(it.mediaId) as MediaId.Track }
        assertTrue(appended.all { it.container == MediaId.HomeRandomMix })
    }

    @Test
    fun `appended batch excludes tracks already in the queue`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(
            similar = listOf(stubTrack("tr-2", "ar-x")) + (1..5).map { stubTrack("s-$it", "ar-$it") },
        )
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()

        val appendedIds = player.items.drop(3).map { (MediaId.parse(it.mediaId) as MediaId.Track).id }
        assertTrue("tr-2" !in appendedIds)
    }

    @Test
    fun `no seed anywhere in the scan window means no repository call`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(failure = MusicException.NetworkError(RuntimeException("boom")))
        val listener = listener(player, repo, seeds = emptyMap())

        transitionToLast(player, listener)
        runCurrent()

        assertEquals(3, player.items.size)
    }

    @Test
    fun `empty answer exhausts the queue until a new one is set`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        var calls = 0
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
                calls++
                return emptyList()
            }
        }
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()
        transitionToLast(player, listener)
        runCurrent()
        assertEquals(1, calls, "exhausted queue was probed again")

        // A new queue re-arms the listener.
        player.index = 0
        listener.onMediaItemTransition(
            player.items.first(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        runCurrent()
        transitionToLast(player, listener)
        runCurrent()
        assertEquals(2, calls, "new queue did not re-arm")
    }

    @Test
    fun `a failed fetch leaves the listener armed`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        var attempts = 0
        val similar = (1..5).map { stubTrack("s-$it", "ar-$it") }
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
                attempts++
                if (attempts == 1) throw MusicException.NetworkError(RuntimeException("offline"))
                return similar
            }
        }
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()
        assertEquals(3, player.items.size)

        transitionToLast(player, listener)
        runCurrent()
        assertTrue(player.items.size > 3, "listener did not retry after a transient failure")
    }

    @Test
    fun `a stale batch is dropped when the queue changed mid-fetch`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        val repo = stubRepository(
            similar = (1..5).map { stubTrack("s-$it", "ar-$it") },
            callDelayMs = 1_000,
        )
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()
        // The user starts a different queue while the fetch is in flight.
        player.items.clear()
        player.items += (1..4).map { item("other-$it", MediaId.Album("al-2")) }
        player.index = 0
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(4, player.items.size, "stale batch was appended")
    }

    @Test
    fun `single-flight ignores re-triggers while a fetch runs`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        var calls = 0
        val similar = (1..5).map { stubTrack("s-$it", "ar-$it") }
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
                calls++
                delay(1_000)
                return similar
            }
        }
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()
        transitionToLast(player, listener)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(1, calls)
    }

    @Test
    fun `full queues stop extending`() = runTest {
        val player = QueuePlayer()
        player.items += (1..QueueRadioListener.MAX_QUEUE_ITEMS).map {
            item("tr-$it", MediaId.Album("al-1"))
        }
        var calls = 0
        val repo = object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
                calls++
                return emptyList()
            }
        }
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        transitionToLast(player, listener)
        runCurrent()

        assertEquals(0, calls)
        assertEquals(QueueRadioListener.MAX_QUEUE_ITEMS, player.items.size)
    }

    @Test
    fun `ended backstop appends and resumes only when play-when-ready`() = runTest {
        val player = QueuePlayer()
        albumQueue(player)
        player.index = 2
        player.state = Player.STATE_ENDED
        val repo = stubRepository(similar = (1..5).map { stubTrack("s-$it", "ar-$it") })
        val listener = listener(player, repo, seeds = mapOf(player.items.last().mediaId to "ar-seed"))

        player.ready = false
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()
        assertTrue(player.items.size > 3, "ended backstop did not extend")
        assertEquals(emptyList(), player.seeks, "paused player was seeked")

        // Re-arm via a new queue, then repeat with playWhenReady set.
        player.items.subList(3, player.items.size).clear()
        player.index = 0
        listener.onMediaItemTransition(
            player.items.first(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )
        runCurrent()
        player.index = 2
        player.ready = true
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()
        assertEquals(listOf(3), player.seeks, "playing player was not resumed into the batch")
    }
}
