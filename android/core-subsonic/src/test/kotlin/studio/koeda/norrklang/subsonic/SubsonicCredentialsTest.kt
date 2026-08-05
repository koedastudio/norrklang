package studio.koeda.norrklang.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals(SubsonicAuth.token("sesame", creds.auth.salt), creds.auth.token)
    }
}
