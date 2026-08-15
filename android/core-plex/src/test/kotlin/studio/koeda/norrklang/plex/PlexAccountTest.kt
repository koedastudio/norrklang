package studio.koeda.norrklang.plex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlexAccountTest {

    @Test
    fun `account toString never contains the token`() {
        val account = PlexAccount(
            serverUri = "https://vault.example.com:32400",
            serverName = "Vault",
            machineIdentifier = "m1",
            token = "super-secret-token",
            sectionId = "5",
            username = "demo",
        )
        assertTrue("super-secret-token" !in account.toString())
    }

    @Test
    fun `fingerprint is stable and token-sensitive`() {
        fun account(token: String) = PlexAccount(
            serverUri = "https://vault.example.com:32400",
            serverName = "Vault",
            machineIdentifier = "m1",
            token = token,
            sectionId = "5",
            username = "demo",
        )
        assertEquals(account("t1").cacheFingerprint, account("t1").cacheFingerprint)
        assertEquals(16, account("t1").cacheFingerprint.length)
        assertTrue(account("t1").cacheFingerprint != account("t2").cacheFingerprint)
    }
}
