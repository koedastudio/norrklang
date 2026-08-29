package studio.koeda.norrklang.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinUrlBuilderTest {

    private val urls =
        JellyfinUrlBuilder("https://jf.example.com", "srv-token", "user-1", "device-1")

    @Test
    fun `streamUrl requests the original file with the token in the query`() {
        assertEquals(
            "https://jf.example.com/Audio/100/stream?static=true&api_key=srv-token",
            urls.streamUrl("100"),
        )
    }

    @Test
    fun `capped streamUrl goes through the universal audio endpoint`() {
        val url = urls.streamUrl("100", maxKbps = 192)
        assertTrue(url.startsWith("https://jf.example.com/Audio/100/universal?"))
        assertTrue("UserId=user-1" in url)
        assertTrue("&DeviceId=device-1" in url)
        assertTrue("&MaxStreamingBitrate=192000" in url)
        assertTrue("&TranscodingContainer=mp3" in url)
        assertTrue("&api_key=srv-token" in url)
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
        val urls = JellyfinUrlBuilder("https://jf.example.com", "to ken&x", "u", "d")
        assertTrue("api_key=to+ken%26x" in urls.streamUrl("100"))
    }
}
