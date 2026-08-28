package studio.koeda.norrklang.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinUrlBuilderTest {

    private val urls = JellyfinUrlBuilder("https://jf.example.com", "srv-token")

    @Test
    fun `streamUrl requests the original file with the token in the query`() {
        assertEquals(
            "https://jf.example.com/Audio/100/stream?static=true&api_key=srv-token",
            urls.streamUrl("100"),
        )
    }

    @Test
    fun `artworkUrl requests a scaled primary image`() {
        val url = urls.artworkUrl("101", size = 512)
        assertTrue(url.startsWith("https://jf.example.com/Items/101/Images/Primary?"))
        assertTrue("fillWidth=512" in url)
        assertTrue("fillHeight=512" in url)
        assertTrue("api_key=srv-token" in url)
    }

    @Test
    fun `urls encode reserved characters`() {
        val urls = JellyfinUrlBuilder("https://jf.example.com", "to ken&x")
        assertTrue("api_key=to+ken%26x" in urls.streamUrl("100"))
    }
}
