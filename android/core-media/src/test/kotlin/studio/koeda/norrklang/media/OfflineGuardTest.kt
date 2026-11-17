package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.repo.MusicException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineGuardTest {

    private val empty: (List<String>) -> Boolean = { it.isEmpty() }

    @Test
    fun `offline, what resolves at once goes through`() = runTest {
        val guard = OfflineGuard(isOnline = { false })

        assertEquals(listOf("from-disk"), guard.run(empty) { listOf("from-disk") })
    }

    @Test
    fun `offline, a request that hangs ends at the deadline`() = runTest {
        val guard = OfflineGuard(isOnline = { false }, deadlineMs = 2_500)

        assertFailsWith<OfflineGuard.NoConnection> {
            guard.run(empty) {
                delay(60_000)
                listOf("too-late")
            }
        }
        assertEquals(2_500, currentTime)
    }

    @Test
    fun `offline, an empty mix is no connection, not an empty queue`() = runTest {
        val guard = OfflineGuard(isOnline = { false })

        assertFailsWith<OfflineGuard.NoConnection> { guard.run(empty) { emptyList() } }
    }

    @Test
    fun `offline, a list that really is empty passes as it does online`() = runTest {
        val guard = OfflineGuard(isOnline = { false })

        assertEquals(emptyList(), guard.run<List<String>>({ false }) { emptyList() })
    }

    @Test
    fun `only the generated mixes can hide a failure behind an empty list`() {
        val containers = ContainerTracks(
            FakeMusicRepository(),
            RandomMixSession(FakeMusicRepository()),
            SimilarMixesSession(FakeMusicRepository()),
            BestOfMixesSession(FakeMusicRepository()),
            CatalogMixes(FakeMusicRepository()),
        )

        assertTrue(containers.emptyMayBeFailure(MediaId.HomeBestOf("a")))
        assertTrue(containers.emptyMayBeFailure(MediaId.HomeSimilar("a")))
        assertTrue(containers.emptyMayBeFailure(MediaId.GenreMix("Rock")))
        assertFalse(containers.emptyMayBeFailure(MediaId.HomeFavoriteSongs))
        assertFalse(containers.emptyMayBeFailure(MediaId.Playlist("p")))
    }

    @Test
    fun `a network error is no connection, online or not`() = runTest {
        for (online in listOf(true, false)) {
            assertFailsWith<OfflineGuard.NoConnection> {
                OfflineGuard(isOnline = { online }).run(empty) {
                    throw MusicException.NetworkError(IOException("unreachable"))
                }
            }
        }
    }

    @Test
    fun `online, slow and empty results pass as before`() = runTest {
        val guard = OfflineGuard(isOnline = { true }, deadlineMs = 2_500)

        assertEquals(
            listOf("slow"),
            guard.run(empty) {
                delay(60_000)
                listOf("slow")
            },
        )
        assertEquals(emptyList(), guard.run(empty) { emptyList() })
    }

    @Test
    fun `other failures pass through untouched`() = runTest {
        val guard = OfflineGuard(isOnline = { false })

        assertFailsWith<MusicException.NotFound> {
            guard.run(empty) { throw MusicException.NotFound("gone") }
        }
    }
}
