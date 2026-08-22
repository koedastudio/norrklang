package studio.koeda.norrklang.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track

class QueueRadioTest {

    private fun radio(
        similar: List<Track> = emptyList(),
        random: List<Track> = emptyList(),
    ) = QueueRadio(
        object : FakeMusicRepository() {
            override suspend fun similarTracks(artistId: String, count: Int) = similar
            override suspend fun randomTracks(size: Int) = random
        },
        Random(seed = 7),
    )

    @Test
    fun `similar continuation drops tracks already in the queue`() = runTest {
        val tracks = (1..6).map { stubTrack("tr-$it", artistId = "ar-$it") }
        val batch = radio(similar = tracks)
            .similarContinuation("ar-1", excludeTrackIds = setOf("tr-2", "tr-4"))
        assertEquals(setOf("tr-1", "tr-3", "tr-5", "tr-6"), batch.map { it.id }.toSet())
    }

    @Test
    fun `similar continuation caps each artist and interleaves`() = runTest {
        // One artist dominates the server answer; the cap keeps the batch varied.
        val dominant = (1..30).map { stubTrack("a-$it", artistId = "ar-a") }
        val other = (1..10).map { stubTrack("b-$it", artistId = "ar-b") }
        val batch = radio(similar = dominant + other)
            .similarContinuation("ar-a", excludeTrackIds = emptySet())
        assertEquals(
            QueueRadio.PER_ARTIST_CAP,
            batch.count { it.artistId == "ar-a" },
        )
        assertTrue(
            batch.zipWithNext().none { (a, b) -> a.artistId == b.artistId },
            "adjacent same-artist tracks in $batch",
        )
    }

    @Test
    fun `similar continuation is bounded and deduplicates repeats`() = runTest {
        val tracks = (1..40).map { stubTrack("tr-${it % 25}", artistId = "ar-${it % 8}") }
        val batch = radio(similar = tracks).similarContinuation("ar-1", emptySet())
        assertTrue(batch.size <= QueueRadio.APPEND_COUNT)
        assertEquals(batch.size, batch.map { it.id }.distinct().size)
    }

    @Test
    fun `empty server answers yield empty batches`() = runTest {
        assertEquals(emptyList(), radio().similarContinuation("ar-1", emptySet()))
        assertEquals(emptyList(), radio().randomContinuation(emptySet()))
    }

    @Test
    fun `random continuation dedupes and caps`() = runTest {
        val tracks = (1..40).map { stubTrack("tr-$it") } + stubTrack("tr-1")
        val batch = radio(random = tracks).randomContinuation(setOf("tr-2"))
        assertTrue(batch.size <= QueueRadio.APPEND_COUNT)
        assertTrue(batch.none { it.id == "tr-2" })
        assertEquals(batch.size, batch.map { it.id }.distinct().size)
    }
}
