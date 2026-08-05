package studio.koeda.norrklang.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CacheFingerprintTest {

    private fun credentials(
        url: String = "https://music.example.com",
        user: String = "demo",
        salt: String = "abcdef",
        token: String = "0123456789abcdef0123456789abcdef",
    ) = SubsonicCredentials(url, user, SubsonicTokenAuth(salt, token))

    @Test
    fun `identical credentials share a fingerprint`() {
        assertEquals(credentials().cacheFingerprint, credentials().cacheFingerprint)
    }

    @Test
    fun `fingerprint differs per server`() {
        assertNotEquals(
            credentials(url = "https://a.example.com").cacheFingerprint,
            credentials(url = "https://b.example.com").cacheFingerprint,
        )
    }

    @Test
    fun `fingerprint differs per account`() {
        assertNotEquals(
            credentials(user = "alice").cacheFingerprint,
            credentials(user = "bob").cacheFingerprint,
        )
    }

    @Test
    fun `fingerprint differs per token`() {
        assertNotEquals(
            credentials(token = "0123456789abcdef0123456789abcdef").cacheFingerprint,
            credentials(token = "fedcba9876543210fedcba9876543210").cacheFingerprint,
        )
    }

    @Test
    fun `fingerprint is filesystem-safe`() {
        val fingerprint = credentials().cacheFingerprint
        assertEquals(16, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
    }
}
