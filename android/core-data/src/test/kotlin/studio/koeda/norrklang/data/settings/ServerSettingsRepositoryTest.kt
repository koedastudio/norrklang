package studio.koeda.norrklang.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicPasswordAuth
import studio.koeda.norrklang.subsonic.SubsonicTokenAuth

/** Reversible stand-in for the Android Keystore cipher (unavailable on JVM). */
private class FakeCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = PREFIX + plaintext.reversed()
    override fun decrypt(stored: String) =
        if (isEncrypted(stored)) stored.removePrefix(PREFIX).reversed() else stored
    override fun isEncrypted(stored: String) = stored.startsWith(PREFIX)

    companion object { const val PREFIX = "enc-test:" }
}

/** Simulates a Keystore whose key has been lost: nothing decrypts anymore. */
private class BrokenCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = FakeCipher.PREFIX + plaintext
    override fun decrypt(stored: String): String? =
        if (isEncrypted(stored)) null else stored
    override fun isEncrypted(stored: String) = stored.startsWith(FakeCipher.PREFIX)
}

class ServerSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val urlKey = stringPreferencesKey("server_url")
    private val userKey = stringPreferencesKey("username")
    private val saltKey = stringPreferencesKey("auth_salt")
    private val tokenKey = stringPreferencesKey("auth_token")
    private val passwordKey = stringPreferencesKey("password")
    private val authPasswordKey = stringPreferencesKey("auth_password")

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private val credentials =
        SubsonicCredentials.fromInput("https://music.example.com", "demo", "secret")
    private val tokenAuth get() = credentials.auth as SubsonicTokenAuth

    @Test
    fun `save stores encrypted values and read round-trips`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.save(credentials)

        val prefs = store.data.first()
        assertTrue(prefs[tokenKey]!!.startsWith(FakeCipher.PREFIX))
        assertTrue(prefs[saltKey]!!.startsWith(FakeCipher.PREFIX))
        assertEquals(credentials, repo.currentCredentials())
    }

    @Test
    fun `plaintext credentials from older installs are read and re-encrypted`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { prefs ->
            prefs[urlKey] = credentials.baseUrl
            prefs[userKey] = credentials.username
            prefs[saltKey] = tokenAuth.salt
            prefs[tokenKey] = tokenAuth.token
        }
        val repo = ServerSettingsRepository(store, FakeCipher())

        assertEquals(credentials, repo.currentCredentials())
        assertTrue(store.data.first()[tokenKey]!!.startsWith(FakeCipher.PREFIX))
    }

    @Test
    fun `legacy plaintext password is migrated to a token and deleted`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { prefs ->
            prefs[urlKey] = "https://music.example.com"
            prefs[userKey] = "demo"
            prefs[passwordKey] = "secret"
        }
        val repo = ServerSettingsRepository(store, FakeCipher())

        val migrated = repo.currentCredentials()
        assertEquals("https://music.example.com", migrated?.baseUrl)
        assertEquals("demo", migrated?.username)
        assertNull(store.data.first()[passwordKey])
    }

    @Test
    fun `undecryptable credentials read as signed out`() = runTest {
        val store = dataStore(backgroundScope)
        ServerSettingsRepository(store, FakeCipher()).save(credentials)

        // FakeCipher and BrokenCipher share a prefix, so the stored values
        // look encrypted but no longer decrypt — like a lost Keystore key.
        assertNull(ServerSettingsRepository(store, BrokenCipher()).currentCredentials())
    }

    @Test
    fun `clearAccount removes credentials, resumption and exclusions`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        repo.saveResumptionState("track/1", 1234L)
        repo.setArtistScrobbleExcluded("artist/1", true)
        repo.setPlaylistScrobbleExcluded("playlist/1", true)
        repo.setLibraryScrobbleExcluded("lib/1", true)
        repo.setLibraryExcluded("lib/2", true)

        repo.clearAccount()

        assertNull(repo.currentCredentials())
        assertNull(repo.resumptionState())
        val scrobble = repo.scrobbleSettings.first()
        assertTrue(scrobble.excludedArtistIds.isEmpty())
        assertTrue(scrobble.excludedPlaylistIds.isEmpty())
        assertTrue(scrobble.excludedLibraryIds.isEmpty())
        assertTrue(repo.excludedLibraryIds.first().isEmpty())
    }

    @Test
    fun `clearAccount keeps device-wide preferences`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        repo.setStreamQualityCellular(StreamQuality.HIGH)
        repo.setScrobblingEnabled(false)
        repo.setAutoplaySimilar(false)

        repo.clearAccount()

        assertEquals(StreamQuality.HIGH, repo.streamQualityCellular.first())
        assertEquals(false, repo.scrobbleSettings.first().enabled)
        assertEquals(false, repo.autoplaySimilar.first())
    }

    private val plexAccount = PlexAccount(
        serverUri = "https://vault.example.com:32400",
        serverName = "Vault",
        machineIdentifier = "m1",
        token = "plex-token",
        username = "demo",
    )

    @Test
    fun `plex account round-trips with an encrypted token`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.savePlex(plexAccount)

        val stored = assertIs<StoredAccount.Plex>(repo.currentAccount())
        assertEquals(plexAccount, stored.account)
        // The token never lands in the store as plaintext.
        val rawToken = store.data.first()[stringPreferencesKey("plex_token")]
        assertEquals(FakeCipher.PREFIX + "plex-token".reversed(), rawToken)
    }

    @Test
    fun `provider-less prefs with subsonic keys read as a subsonic account`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        // Simulate a pre-Plex install: no provider discriminator on disk.
        store.edit { it.remove(stringPreferencesKey("provider")) }

        val stored = assertIs<StoredAccount.Subsonic>(repo.currentAccount())
        assertEquals(credentials, stored.credentials)
    }

    @Test
    fun `saving one provider removes the other`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.save(credentials)
        repo.savePlex(plexAccount)
        assertIs<StoredAccount.Plex>(repo.currentAccount())
        assertNull(repo.currentCredentials())

        repo.save(credentials)
        assertIs<StoredAccount.Subsonic>(repo.currentAccount())
        assertNull(store.data.first()[stringPreferencesKey("plex_token")])
    }

    private val jellyfinAccount = JellyfinAccount(
        baseUrl = "https://jf.example.com",
        serverName = "Vault",
        userId = "u1",
        username = "demo",
        token = "jf-token",
    )

    @Test
    fun `jellyfin account round-trips with an encrypted token`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.saveJellyfin(jellyfinAccount)

        val stored = assertIs<StoredAccount.Jellyfin>(repo.currentAccount())
        assertEquals(jellyfinAccount, stored.account)
        // The token never lands in the store as plaintext.
        val rawToken = store.data.first()[stringPreferencesKey("jellyfin_token")]
        assertEquals(FakeCipher.PREFIX + "jf-token".reversed(), rawToken)
    }

    @Test
    fun `saving jellyfin removes the other providers and vice versa`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.savePlex(plexAccount)
        repo.saveJellyfin(jellyfinAccount)
        assertIs<StoredAccount.Jellyfin>(repo.currentAccount())
        assertNull(store.data.first()[stringPreferencesKey("plex_token")])

        repo.save(credentials)
        assertIs<StoredAccount.Subsonic>(repo.currentAccount())
        assertNull(store.data.first()[stringPreferencesKey("jellyfin_token")])
    }

    @Test
    fun `jellyfin device id is minted once and survives clearAccount`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        val first = repo.jellyfinDeviceId()
        assertEquals(first, repo.jellyfinDeviceId())

        repo.saveJellyfin(jellyfinAccount)
        repo.clearAccount()

        assertNull(repo.currentAccount())
        assertEquals(first, repo.jellyfinDeviceId())
        assertNotEquals("", first)
    }

    @Test
    fun `plex client id is minted once and survives clearAccount`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        val first = repo.plexClientId()
        assertEquals(first, repo.plexClientId())

        repo.savePlex(plexAccount)
        repo.clearAccount()

        assertNull(repo.currentAccount())
        assertEquals(first, repo.plexClientId())
        assertNotEquals("", first)
    }

    @Test
    fun `stream quality defaults to original on wifi, capped on cellular, and round-trips`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        assertEquals(StreamQuality.ORIGINAL, repo.streamQualityWifi.first())
        assertEquals(StreamQuality.HIGH, repo.streamQualityCellular.first())

        repo.setStreamQualityWifi(StreamQuality.MEDIUM)
        repo.setStreamQualityCellular(StreamQuality.LOW)
        assertEquals(StreamQuality.MEDIUM, repo.streamQualityWifi.first())
        assertEquals(StreamQuality.LOW, repo.streamQualityCellular.first())
    }

    @Test
    fun `legacy stream-original off falls back to the highest capped tier`() = runTest {
        val store = dataStore(backgroundScope)
        store.edit { it[booleanPreferencesKey("stream_original")] = false }
        val repo = ServerSettingsRepository(store, FakeCipher())

        assertEquals(StreamQuality.HIGH, repo.streamQualityWifi.first())
        assertEquals(StreamQuality.HIGH, repo.streamQualityCellular.first())

        // An explicit choice wins over the legacy fallback.
        repo.setStreamQualityWifi(StreamQuality.ORIGINAL)
        assertEquals(StreamQuality.ORIGINAL, repo.streamQualityWifi.first())
        assertEquals(StreamQuality.HIGH, repo.streamQualityCellular.first())
    }

    @Test
    fun `autoplay similar defaults to true and round-trips`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        assertTrue(repo.autoplaySimilar.first())

        repo.setAutoplaySimilar(false)
        assertEquals(false, repo.autoplaySimilar.first())
    }

    @Test
    fun `resumption state round-trips`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        repo.saveResumptionState("track/42", 90_000L)
        assertEquals(
            ServerSettingsRepository.ResumptionState("track/42", 90_000L),
            repo.resumptionState(),
        )
    }

    @Test
    fun `a queued resumption save cannot revive state after sign-out`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        repo.save(credentials)
        val revision = repo.accountRevision()!!
        repo.clearAccount()
        repo.saveResumptionState("track/old", 12_000, revision)
        assertNull(repo.resumptionState())
    }

    @Test
    fun `switching accounts clears server ids and rejects the old persister`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        repo.save(credentials)
        val revision = repo.accountRevision()!!
        repo.saveResumptionState("track/old", 12_000, revision)
        repo.setArtistScrobbleExcluded("old-artist", true)
        repo.setLibraryExcluded("old-lib", true)
        repo.save(SubsonicCredentials.fromInput("https://other.example.com", "bob", "secret"))
        repo.saveResumptionState("track/old", 20_000, revision)
        assertNull(repo.resumptionState())
        assertTrue(repo.scrobbleSettings.first().excludedArtistIds.isEmpty())
        assertTrue(repo.excludedLibraryIds.first().isEmpty())
        assertNotEquals(revision, repo.accountRevision())
    }

    // --- Libraries ---

    private val excludedKey = stringSetPreferencesKey("libraries_excluded")
    private val plexSectionKey = stringPreferencesKey("plex_section_id")
    private val jellyfinLibraryKey = stringPreferencesKey("jellyfin_library_id")

    @Test
    fun `library exclusions default to empty and round-trip`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        assertTrue(repo.excludedLibraryIds.first().isEmpty())

        repo.setLibraryExcluded("2", true)
        repo.setLibraryExcluded("3", true)
        repo.setLibraryExcluded("2", false)

        assertEquals(setOf("3"), repo.excludedLibraryIds.first())
    }

    @Test
    fun `library scrobble exclusions round-trip`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        repo.setLibraryScrobbleExcluded("2", true)
        assertEquals(setOf("2"), repo.scrobbleSettings.first().excludedLibraryIds)
    }

    @Test
    fun `savePlex stores the picker selection despite clearing the old account`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        repo.setLibraryExcluded("old", true)

        repo.savePlex(plexAccount, excludedLibraryIds = setOf("6"))

        assertEquals(setOf("6"), repo.excludedLibraryIds.first())
        assertNull(store.data.first()[plexSectionKey])
    }

    @Test
    fun `re-saving the same account with an empty selection clears it`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())
        repo.saveJellyfin(jellyfinAccount, excludedLibraryIds = setOf("lib2"))
        repo.saveJellyfin(jellyfinAccount)
        assertTrue(repo.excludedLibraryIds.first().isEmpty())
    }

    @Test
    fun `legacy plex section seeds the exclusion set and drops the key`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.savePlex(plexAccount)
        store.edit { it[plexSectionKey] = "5" }

        repo.migrateLegacyLibrarySelection(listOf("5", "6", "7"))

        assertEquals(setOf("6", "7"), repo.excludedLibraryIds.first())
        assertNull(store.data.first()[plexSectionKey])
        // Idempotent: a second run has nothing to migrate.
        repo.setLibraryExcluded("6", false)
        repo.migrateLegacyLibrarySelection(listOf("5", "6", "7"))
        assertEquals(setOf("7"), repo.excludedLibraryIds.first())
    }

    @Test
    fun `legacy jellyfin library missing on the server only drops the key`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.saveJellyfin(jellyfinAccount)
        store.edit { it[jellyfinLibraryKey] = "gone" }

        repo.migrateLegacyLibrarySelection(listOf("lib1", "lib2"))

        assertTrue(repo.excludedLibraryIds.first().isEmpty())
        assertNull(store.data.first()[jellyfinLibraryKey])
    }

    @Test
    fun `legacy migration never overwrites an existing selection`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.savePlex(plexAccount, excludedLibraryIds = setOf("7"))
        store.edit { it[plexSectionKey] = "5" }

        repo.migrateLegacyLibrarySelection(listOf("5", "6", "7"))

        assertEquals(setOf("7"), repo.excludedLibraryIds.first())
        assertNull(store.data.first()[plexSectionKey])
    }

    @Test
    fun `legacy migration is a no-op for subsonic`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        store.edit { it[plexSectionKey] = "5" }

        repo.migrateLegacyLibrarySelection(listOf("5", "6"))

        assertTrue(repo.excludedLibraryIds.first().isEmpty())
        assertEquals("5", store.data.first()[plexSectionKey])
    }

    @Test
    fun `password-auth credentials round-trip encrypted without a token pair`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        val passwordCredentials = credentials.withPasswordAuth("secret")

        repo.save(passwordCredentials)

        val prefs = store.data.first()
        assertTrue(prefs[authPasswordKey]!!.startsWith(FakeCipher.PREFIX))
        assertTrue("secret" !in prefs[authPasswordKey]!!)
        assertNull(prefs[saltKey])
        assertNull(prefs[tokenKey])
        assertEquals(passwordCredentials, repo.currentCredentials())
        assertIs<SubsonicPasswordAuth>(repo.currentCredentials()!!.auth)
    }

    @Test
    fun `switching auth mode for the same account replaces the stored secret`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())

        repo.save(credentials.withPasswordAuth("secret"))
        repo.save(credentials)
        assertNull(store.data.first()[authPasswordKey])
        assertIs<SubsonicTokenAuth>(repo.currentCredentials()!!.auth)

        repo.save(credentials.withPasswordAuth("secret"))
        assertNull(store.data.first()[saltKey])
        assertNull(store.data.first()[tokenKey])
        assertIs<SubsonicPasswordAuth>(repo.currentCredentials()!!.auth)
    }

    @Test
    fun `clearAccount removes a stored password`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials.withPasswordAuth("secret"))

        repo.clearAccount()

        assertNull(store.data.first()[authPasswordKey])
        assertNull(repo.currentCredentials())
    }

    @Test
    fun `an undecryptable stored password reads as signed out`() = runTest {
        val store = dataStore(backgroundScope)
        ServerSettingsRepository(store, FakeCipher()).save(credentials.withPasswordAuth("secret"))

        assertNull(ServerSettingsRepository(store, BrokenCipher()).currentCredentials())
    }

    @Test
    fun `radio plays accumulate per station and rank by count then recency`() = runTest {
        val repo = ServerSettingsRepository(dataStore(backgroundScope), FakeCipher())

        repo.recordRadioPlay("rs-a", nowMs = 1_000)
        repo.recordRadioPlay("rs-b", nowMs = 2_000)
        repo.recordRadioPlay("rs-a", nowMs = 3_000)
        repo.recordRadioPlay("rs|c", nowMs = 4_000)

        val stats = repo.radioPlayStats.first()
        assertEquals(listOf("rs-a", "rs|c", "rs-b"), stats.map { it.stationId })
        assertEquals(2, stats[0].playCount)
        assertEquals(3_000, stats[0].lastPlayedMs)
    }

    @Test
    fun `radio play history is account-scoped`() = runTest {
        val store = dataStore(backgroundScope)
        val repo = ServerSettingsRepository(store, FakeCipher())
        repo.save(credentials)
        repo.recordRadioPlay("rs-a")

        repo.clearAccount()

        assertTrue(repo.radioPlayStats.first().isEmpty())
    }
}
