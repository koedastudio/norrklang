package studio.koeda.norrklang.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubsonicCredentialsTest {

    @Test
    fun `fromInput adds https scheme when missing`() {
        val creds = SubsonicCredentials.fromInput("music.example.com", "demo", "pw")
        assertEquals("https://music.example.com", creds.baseUrl)
    }

    @Test
    fun `fromInput keeps explicit http scheme and strips trailing slash`() {
        val creds = SubsonicCredentials.fromInput("http://192.168.1.10:4533/", "demo", "pw")
        assertEquals("http://192.168.1.10:4533", creds.baseUrl)
    }

    @Test
    fun `fromInput trims whitespace`() {
        val creds = SubsonicCredentials.fromInput("  https://x.se  ", " demo ", "pw")
        assertEquals("https://x.se", creds.baseUrl)
        assertEquals("demo", creds.username)
    }

    @Test
    fun `fromInput derives the token from the password and generated salt`() {
        val creds = SubsonicCredentials.fromInput("https://x.se", "demo", "sesame")
        val auth = assertIs<SubsonicTokenAuth>(creds.auth)
        assertEquals(SubsonicAuth.token("sesame", auth.salt), auth.token)
    }

    @Test
    fun `token auth params carry token and salt and no password`() {
        val creds = SubsonicCredentials.fromInput("https://x.se", "demo", "sesame")
        val keys = creds.authParams().map { it.first }
        assertEquals(listOf("u", "t", "s", "v", "c"), keys)
    }

    @Test
    fun `withPasswordAuth keeps server and account and sends the encoded password`() {
        val creds = SubsonicCredentials.fromInput("https://x.se", "demo", "sesame")
            .withPasswordAuth("sesame")
        assertEquals("https://x.se", creds.baseUrl)
        assertEquals("demo", creds.username)
        assertEquals(
            listOf("u" to "demo", "p" to "enc:736573616d65", "v" to "1.16.1", "c" to "norrklang"),
            creds.authParams(),
        )
    }

    @Test
    fun `password auth toString never contains the password`() {
        val creds = SubsonicCredentials.fromInput("https://x.se", "demo", "sesame")
            .withPasswordAuth("sesame")
        assertTrue("sesame" !in creds.toString())
        assertTrue("sesame" !in creds.auth.toString())
    }

    @Test
    fun `token and password auth for the same account have distinct cache fingerprints`() {
        val token = SubsonicCredentials.fromInput("https://x.se", "demo", "sesame")
        assertNotEquals(token.cacheFingerprint, token.withPasswordAuth("sesame").cacheFingerprint)
    }
}
