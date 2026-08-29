package studio.koeda.norrklang.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.subsonic.SubsonicCredentials

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

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private val credentials =
        SubsonicCredentials.fromInput("https://music.example.com", "demo", "secret")

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
            prefs[saltKey] = credentials.auth.salt
            prefs[tokenKey] = credentials.auth.token
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

        repo.clearAccount()

        assertNull(repo.currentCredentials())
        assertNull(repo.resumptionState())
        val scrobble = repo.scrobbleSettings.first()
        assertTrue(scrobble.excludedArtistIds.isEmpty())
        assertTrue(scrobble.excludedPlaylistIds.isEmpty())
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
        sectionId = "5",
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
        libraryId = "lib1",
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
}
