package studio.koeda.norrklang.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubsonicAuthTest {

    @Test
    fun `token matches the reference vector from the Subsonic API docs`() {
        // From http://www.subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d"
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test
    fun `salt has requested length and hex charset`() {
        val salt = SubsonicAuth.generateSalt(16)
        assertEquals(16, salt.length)
        assertTrue(salt.all { it in "abcdef0123456789" })
    }

    @Test
    fun `salts are unique across generations`() {
        val salts = List(100) { SubsonicAuth.generateSalt() }.toSet()
        assertEquals(100, salts.size)
    }

    @Test
    fun `different salts give different tokens`() {
        assertNotEquals(
            SubsonicAuth.token("password", "aaaaaa"),
            SubsonicAuth.token("password", "bbbbbb"),
        )
    }


    @Test
    fun `encodePassword hex-encodes the utf-8 bytes behind the enc prefix`() {
        assertEquals("enc:736573616d65", SubsonicAuth.encodePassword("sesame"))
        assertEquals("enc:70c3a4", SubsonicAuth.encodePassword("pä"))
    }
}
