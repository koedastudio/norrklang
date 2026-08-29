package studio.koeda.norrklang.plex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexUrlBuilderTest {

    private val clientInfo = PlexClientInfo(clientId = "client-1", version = "1.0")
    private val urls = PlexUrlBuilder("https://vault.example.com:32400", "srv-token", clientInfo)

    @Test
    fun `partUrl appends the token to the part path`() {
        assertEquals(
            "https://vault.example.com:32400/library/parts/1/2/file.flac?X-Plex-Token=srv-token",
            urls.partUrl("/library/parts/1/2/file.flac"),
        )
    }

    @Test
    fun `streamUrl direct-plays the part at original quality`() {
        assertEquals(
            urls.partUrl("/library/parts/1/2/file.flac"),
            urls.streamUrl("100", "/library/parts/1/2/file.flac"),
        )
    }

    @Test
    fun `capped streamUrl goes through the universal transcoder`() {
        val url = urls.streamUrl("100", "/library/parts/1/2/file.flac", maxKbps = 320)
        assertTrue(
            url.startsWith(
                "https://vault.example.com:32400/music/:/transcode/universal/start.mp3?",
            ),
        )
        assertTrue("path=%2Flibrary%2Fmetadata%2F100" in url)
        assertTrue("&musicBitrate=320" in url)
        assertTrue("&directPlay=0&directStream=0" in url)
        assertTrue("&protocol=http" in url)
        assertTrue("&X-Plex-Token=srv-token" in url)
        assertTrue("&X-Plex-Client-Identifier=client-1" in url)
    }

    @Test
    fun `capped streamUrl uses one transcode session per track`() {
        // PMS kills a running transcode when a new one starts under the same
        // session — the preloaded next track must not cancel the current one.
        val a = urls.streamUrl("100", "/p/a", maxKbps = 320)
        val b = urls.streamUrl("200", "/p/b", maxKbps = 320)
        assertTrue("session=client-1-100" in a)
        assertTrue("session=client-1-200" in b)
    }

    @Test
    fun `artworkUrl encodes the thumb path and requests a scaled transcode`() {
        val url = urls.artworkUrl("/library/metadata/101/thumb/1699999999", size = 512)
        assertTrue(url.startsWith("https://vault.example.com:32400/photo/:/transcode?"))
        assertTrue("width=512" in url)
        assertTrue("height=512" in url)
        assertTrue("url=%2Flibrary%2Fmetadata%2F101%2Fthumb%2F1699999999" in url)
        assertTrue("X-Plex-Token=srv-token" in url)
    }
}
