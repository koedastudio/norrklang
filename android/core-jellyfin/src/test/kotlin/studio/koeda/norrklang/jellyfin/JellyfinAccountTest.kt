package studio.koeda.norrklang.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinAccountTest {

    @Test
    fun `account toString never contains the token`() {
        val account = JellyfinAccount(
            baseUrl = "https://jf.example.com",
            serverName = "Vault",
            userId = "u1",
            username = "demo",
            token = "super-secret-token",
            libraryId = "lib1",
        )
        assertTrue("super-secret-token" !in account.toString())
    }

    @Test
    fun `fingerprint is stable and token-sensitive`() {
        fun account(token: String) = JellyfinAccount(
            baseUrl = "https://jf.example.com",
            serverName = "Vault",
            userId = "u1",
            username = "demo",
            token = token,
            libraryId = "lib1",
        )
        assertEquals(account("t1").cacheFingerprint, account("t1").cacheFingerprint)
        assertEquals(16, account("t1").cacheFingerprint.length)
        assertTrue(account("t1").cacheFingerprint != account("t2").cacheFingerprint)
    }

    @Test
    fun `normalizeBaseUrl defaults to https and drops trailing slashes`() {
        assertEquals(
            "https://jf.example.com",
            JellyfinAccount.normalizeBaseUrl(" jf.example.com/ "),
        )
        assertEquals(
            "http://jf.example.com:8096",
            JellyfinAccount.normalizeBaseUrl("http://jf.example.com:8096/"),
        )
    }

    @Test
    fun `normalizeBaseUrl preserves a base path`() {
        // Jellyfin is commonly hosted under a path (demo.jellyfin.org/stable).
        assertEquals(
            "https://demo.jellyfin.org/stable",
            JellyfinAccount.normalizeBaseUrl("demo.jellyfin.org/stable/"),
        )
    }
}
