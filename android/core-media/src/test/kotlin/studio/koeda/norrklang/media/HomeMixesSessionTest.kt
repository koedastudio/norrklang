package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track

class HomeMixesSessionTest {
    private class Mixes(private val generateSnapshot: suspend () -> List<String>) :
        HomeMixesSession<String, List<String>>(FakeMusicRepository()) {
        override suspend fun generate() = generateSnapshot()
        override fun isEmpty(snapshot: List<String>) = snapshot.isEmpty()
        override fun sectionTracks(snapshot: List<String>) = emptyMap<String, List<Track>>()
        override suspend fun buildTracksFor(key: String) = emptyList<Track>()
        suspend fun current() = currentSnapshot()
    }

    @Test
    fun `a late generation cannot overwrite the replacement account's mixes`() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<List<String>>()
        var first = true
        val mixes = Mixes {
            if (first) {
                first = false
                oldStarted.complete(Unit)
                oldResponse.await()
            } else {
                listOf("new-account-mix")
            }
        }
        val oldRefresh = async { mixes.refresh("old-account") }
        oldStarted.await()
        assertTrue(mixes.refresh("new-account"))
        oldResponse.complete(listOf("old-account-mix"))
        assertFalse(oldRefresh.await())
        assertEquals(listOf("new-account-mix"), mixes.current())
    }
}
