package studio.koeda.norrklang.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsSanitizeTest {

    @Test
    fun `strips subsonic credentials from a ktor timeout message`() {
        val message = "Connect timeout has expired " +
            "[url=https://music.example:4533/rest/getPlaylists.view" +
            "?u=fredrik&t=deadbeef&s=abc123&v=1.16.1&c=norrklang&f=json, " +
            "connect_timeout=10000 ms]"
        val out = Diagnostics.sanitize(message)
        assertFalse("deadbeef" in out, out)
        assertFalse("abc123" in out, out)
        assertFalse("music.example" in out, out)
        assertTrue("/rest/getPlaylists.view" in out, out)
        assertTrue("connect_timeout=10000 ms" in out, out)
    }

    @Test
    fun `strips plex token and server hash from a stream url`() {
        val message = "Response code: 403 " +
            "https://10-0-1-2.86b608f48d.plex.direct:32400/library/parts/9/1/file.flac" +
            "?X-Plex-Token=tok123"
        val out = Diagnostics.sanitize(message)
        assertFalse("tok123" in out, out)
        assertFalse("plex.direct" in out, out)
        assertTrue("/library/parts/9/1/file.flac" in out, out)
        assertTrue("Response code: 403" in out, out)
    }

    @Test
    fun `plain text passes through`() {
        assertEquals(
            "ServerError: Server reported status 'failed'",
            Diagnostics.sanitize("ServerError: Server reported status 'failed'"),
        )
    }
}
