package studio.koeda.norrklang.plex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexUrlBuilderTest {

    private val urls = PlexUrlBuilder("https://vault.example.com:32400", "srv-token")

    @Test
    fun `partUrl appends the token to the part path`() {
        assertEquals(
            "https://vault.example.com:32400/library/parts/1/2/file.flac?X-Plex-Token=srv-token",
            urls.partUrl("/library/parts/1/2/file.flac"),
        )
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
