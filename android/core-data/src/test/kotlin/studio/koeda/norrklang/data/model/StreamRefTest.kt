package studio.koeda.norrklang.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import studio.koeda.norrklang.data.session.MusicProvider

class StreamRefTest {

    @Test
    fun `round-trips a plain track id`() {
        val ref = StreamRef(MusicProvider.SUBSONIC, "track-42")
        assertEquals("norrklang-stream://subsonic?id=track-42", ref.encode())
        assertEquals(ref, StreamRef.parse(ref.encode()))
    }

    @Test
    fun `round-trips a plex ref with its part key`() {
        val ref = StreamRef(MusicProvider.PLEX, "100", "/library/parts/1/2/file.flac")
        assertEquals(ref, StreamRef.parse(ref.encode()))
    }

    @Test
    fun `round-trips ids with reserved characters`() {
        val ref = StreamRef(MusicProvider.JELLYFIN, "id with space & ampersand ?=")
        assertEquals(ref, StreamRef.parse(ref.encode()))
    }

    @Test
    fun `rejects foreign uris`() {
        assertNull(StreamRef.parse("https://music.example.com/rest/stream?id=1"))
        assertNull(StreamRef.parse("content://studio.koeda.norrklang.artwork/cover/1"))
        assertNull(StreamRef.parse("norrklang-stream://subsonic"))
        assertNull(StreamRef.parse("norrklang-stream://unknown?id=1"))
        assertNull(StreamRef.parse("norrklang-stream://subsonic?part=only"))
    }
}
