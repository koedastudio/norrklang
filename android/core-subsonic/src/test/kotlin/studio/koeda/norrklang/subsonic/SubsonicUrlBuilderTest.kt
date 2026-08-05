package studio.koeda.norrklang.subsonic

import kotlin.test.Test
import kotlin.test.assertTrue

class SubsonicUrlBuilderTest {

    private val credentials = SubsonicCredentials(
        baseUrl = "https://music.example.com",
        username = "demo",
        auth = SubsonicTokenAuth(salt = "c19b2d", token = SubsonicAuth.token("secret", "c19b2d")),
    )

    private val builder = SubsonicUrlBuilder(credentials)

    @Test
    fun `stream url contains endpoint, id and all auth params`() {
        val url = builder.streamUrl("track-42")
        assertTrue(url.startsWith("https://music.example.com/rest/stream?"))
        assertTrue("&id=track-42" in url)
        assertTrue("u=demo" in url)
        assertTrue("&t=" in url)
        assertTrue("&s=" in url)
        assertTrue("&v=1.16.1" in url)
        assertTrue("&c=norrklang" in url)
    }

    @Test
    fun `stream url requests the raw file by default so gapless metadata survives`() {
        assertTrue("&format=raw" in builder.streamUrl("track-42"))
    }

    @Test
    fun `stream url omits format when transcoding is allowed`() {
        val url = builder.withStreamOriginal(false).streamUrl("track-42")
        assertTrue("format=" !in url)
        assertTrue("&id=track-42" in url)
    }

    @Test
    fun `withStreamOriginal is a no-op when the preference already matches`() {
        assertTrue(builder.withStreamOriginal(true) === builder)
    }

    @Test
    fun `cover art url carries id and size`() {
        val url = builder.coverArtUrl("al-7", size = 256)
        assertTrue("/rest/getCoverArt?" in url)
        assertTrue("&id=al-7" in url)
        assertTrue("&size=256" in url)
    }

    @Test
    fun `urls are stable across calls for cacheability`() {
        assertTrue(builder.streamUrl("x") == builder.streamUrl("x"))
    }

    @Test
    fun `urls carry the credentials own salt and token verbatim`() {
        val url = builder.streamUrl("x")
        assertTrue("&s=c19b2d" in url)
        assertTrue("&t=${credentials.auth.token}" in url)
    }
}
