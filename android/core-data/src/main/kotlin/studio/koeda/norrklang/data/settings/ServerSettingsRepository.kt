package studio.koeda.norrklang.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicTokenAuth

/**
 * Persists the configured server + account in Preferences DataStore.
 *
 * Only the fixed (salt, token) pair is stored — never the password (Subsonic
 * accepts a reused salt, see [SubsonicTokenAuth]). The token is
 * password-equivalent, so it is encrypted at rest with an Android Keystore
 * key ([CredentialCipher]); both app manifests set `allowBackup=false` so
 * nothing here ever leaves the device in a backup.
 */
@Singleton
class ServerSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) {

    private object Keys {
        /**
         * "subsonic" | "plex" | "jellyfin". Absent on pre-Plex installs —
         * [SERVER_URL] present then implies Subsonic, so existing users stay
         * signed in without a migration write.
         */
        val PROVIDER = stringPreferencesKey("provider")

        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val AUTH_SALT = stringPreferencesKey("auth_salt")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")

        /**
         * X-Plex-Client-Identifier — Plex's device identity for this install.
         * Minted once and NEVER cleared, even on sign-out: re-linking with a
         * new identifier would register a duplicate device on the account.
         */
        val PLEX_CLIENT_ID = stringPreferencesKey("plex_client_id")
        val PLEX_TOKEN = stringPreferencesKey("plex_token")
        val PLEX_SERVER_URI = stringPreferencesKey("plex_server_uri")
        val PLEX_SERVER_NAME = stringPreferencesKey("plex_server_name")
        val PLEX_MACHINE_ID = stringPreferencesKey("plex_machine_id")
        val PLEX_SECTION_ID = stringPreferencesKey("plex_section_id")
        val PLEX_USERNAME = stringPreferencesKey("plex_username")

        /**
         * Jellyfin's DeviceId — the device identity for this install. Minted
         * once and NEVER cleared, even on sign-out: signing in with a new id
         * would register a duplicate device on the server's dashboard.
         */
        val JELLYFIN_DEVICE_ID = stringPreferencesKey("jellyfin_device_id")
        val JELLYFIN_TOKEN = stringPreferencesKey("jellyfin_token")
        val JELLYFIN_BASE_URL = stringPreferencesKey("jellyfin_base_url")
        val JELLYFIN_SERVER_NAME = stringPreferencesKey("jellyfin_server_name")
        val JELLYFIN_USER_ID = stringPreferencesKey("jellyfin_user_id")
        val JELLYFIN_USERNAME = stringPreferencesKey("jellyfin_username")
        val JELLYFIN_LIBRARY_ID = stringPreferencesKey("jellyfin_library_id")
        val LAST_MEDIA_ID = stringPreferencesKey("last_media_id")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val STREAM_ORIGINAL = booleanPreferencesKey("stream_original")
        val AUTOPLAY_SIMILAR = booleanPreferencesKey("autoplay_similar")
        val SCROBBLE_ENABLED = booleanPreferencesKey("scrobble_enabled")
        val SCROBBLE_EXCLUDED_ARTISTS = stringSetPreferencesKey("scrobble_excluded_artists")
        val SCROBBLE_EXCLUDED_PLAYLISTS = stringSetPreferencesKey("scrobble_excluded_playlists")

        /** Pre-token builds stored the plaintext password under this key. */
        val LEGACY_PASSWORD = stringPreferencesKey("password")
    }

    // flowOn keeps decrypt (Keystore binder IPC + AES) off the collector's
    // context — a main-thread collector must never do crypto on main.
    val credentials: Flow<SubsonicCredentials?> =
        dataStore.data.map(::decode).flowOn(Dispatchers.IO)

    private fun decode(prefs: Preferences): SubsonicCredentials? {
        val url = prefs[Keys.SERVER_URL] ?: return null
        val user = prefs[Keys.USERNAME] ?: return null
        // decrypt() passes legacy plaintext values through unchanged; null
        // (undecryptable — Keystore key gone) means signed out.
        val salt = prefs[Keys.AUTH_SALT]?.let(cipher::decrypt) ?: return null
        val token = prefs[Keys.AUTH_TOKEN]?.let(cipher::decrypt) ?: return null
        return SubsonicCredentials(url, user, SubsonicTokenAuth(salt, token))
    }

    // Serializes the read-then-save migrations: concurrent first calls could
    // otherwise each mint a fresh salt and double-migrate.
    private val migrationMutex = Mutex()

    suspend fun currentCredentials(): SubsonicCredentials? {
        migrationMutex.withLock {
            // A failed migration (Keystore hiccup mid-encrypt, disk error)
            // must not take down credential restore — the stored values still
            // decode below, and the migration retries on the next call.
            try {
                migrateLegacyPassword()
                encryptLegacyPlaintext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.record("credential-migration", e)
            }
        }
        return credentials.first()
    }

    /** The persisted sign-in, whichever provider it belongs to. */
    suspend fun currentAccount(): StoredAccount? {
        val prefs = dataStore.data.first()
        if (prefs[Keys.PROVIDER] == PROVIDER_PLEX) {
            return decodePlex(prefs)?.let { StoredAccount.Plex(it) }
        }
        if (prefs[Keys.PROVIDER] == PROVIDER_JELLYFIN) {
            return decodeJellyfin(prefs)?.let { StoredAccount.Jellyfin(it) }
        }
        // No provider key (pre-Plex install) or "subsonic": Subsonic path,
        // including its legacy migrations.
        return currentCredentials()?.let { StoredAccount.Subsonic(it) }
    }

    private fun decodePlex(prefs: Preferences): PlexAccount? {
        val uri = prefs[Keys.PLEX_SERVER_URI] ?: return null
        val sectionId = prefs[Keys.PLEX_SECTION_ID] ?: return null
        // null (undecryptable — Keystore key gone) means signed out.
        val token = prefs[Keys.PLEX_TOKEN]?.let(cipher::decrypt) ?: return null
        return PlexAccount(
            serverUri = uri,
            serverName = prefs[Keys.PLEX_SERVER_NAME] ?: uri,
            machineIdentifier = prefs[Keys.PLEX_MACHINE_ID] ?: "",
            token = token,
            sectionId = sectionId,
            username = prefs[Keys.PLEX_USERNAME] ?: "",
        )
    }

    private fun decodeJellyfin(prefs: Preferences): JellyfinAccount? {
        val baseUrl = prefs[Keys.JELLYFIN_BASE_URL] ?: return null
        val userId = prefs[Keys.JELLYFIN_USER_ID] ?: return null
        val libraryId = prefs[Keys.JELLYFIN_LIBRARY_ID] ?: return null
        // null (undecryptable — Keystore key gone) means signed out.
        val token = prefs[Keys.JELLYFIN_TOKEN]?.let(cipher::decrypt) ?: return null
        return JellyfinAccount(
            baseUrl = baseUrl,
            serverName = prefs[Keys.JELLYFIN_SERVER_NAME] ?: baseUrl,
            userId = userId,
            username = prefs[Keys.JELLYFIN_USERNAME] ?: "",
            token = token,
            libraryId = libraryId,
        )
    }

    suspend fun save(credentials: SubsonicCredentials) {
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = PROVIDER_SUBSONIC
            prefs[Keys.SERVER_URL] = credentials.baseUrl
            prefs[Keys.USERNAME] = credentials.username
            prefs[Keys.AUTH_SALT] = cipher.encrypt(credentials.auth.salt)
            prefs[Keys.AUTH_TOKEN] = cipher.encrypt(credentials.auth.token)
            prefs.remove(Keys.LEGACY_PASSWORD)
            removePlexAccount(prefs)
            removeJellyfinAccount(prefs)
        }
    }

    suspend fun savePlex(account: PlexAccount) {
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = PROVIDER_PLEX
            prefs[Keys.PLEX_TOKEN] = cipher.encrypt(account.token)
            prefs[Keys.PLEX_SERVER_URI] = account.serverUri
            prefs[Keys.PLEX_SERVER_NAME] = account.serverName
            prefs[Keys.PLEX_MACHINE_ID] = account.machineIdentifier
            prefs[Keys.PLEX_SECTION_ID] = account.sectionId
            prefs[Keys.PLEX_USERNAME] = account.username
            removeSubsonicAccount(prefs)
            removeJellyfinAccount(prefs)
        }
    }

    suspend fun saveJellyfin(account: JellyfinAccount) {
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = PROVIDER_JELLYFIN
            prefs[Keys.JELLYFIN_TOKEN] = cipher.encrypt(account.token)
            prefs[Keys.JELLYFIN_BASE_URL] = account.baseUrl
            prefs[Keys.JELLYFIN_SERVER_NAME] = account.serverName
            prefs[Keys.JELLYFIN_USER_ID] = account.userId
            prefs[Keys.JELLYFIN_USERNAME] = account.username
            prefs[Keys.JELLYFIN_LIBRARY_ID] = account.libraryId
            removeSubsonicAccount(prefs)
            removePlexAccount(prefs)
        }
    }

    /**
     * This install's X-Plex-Client-Identifier, minted on first use. The edit
     * re-checks under DataStore's own serialization so concurrent first calls
     * agree on one id.
     */
    suspend fun plexClientId(): String {
        dataStore.data.first()[Keys.PLEX_CLIENT_ID]?.let { return it }
        val minted = UUID.randomUUID().toString()
        val prefs = dataStore.edit { prefs ->
            if (prefs[Keys.PLEX_CLIENT_ID] == null) prefs[Keys.PLEX_CLIENT_ID] = minted
        }
        return prefs[Keys.PLEX_CLIENT_ID] ?: minted
    }

    /**
     * This install's Jellyfin DeviceId, minted on first use. The edit
     * re-checks under DataStore's own serialization so concurrent first calls
     * agree on one id.
     */
    suspend fun jellyfinDeviceId(): String {
        dataStore.data.first()[Keys.JELLYFIN_DEVICE_ID]?.let { return it }
        val minted = UUID.randomUUID().toString()
        val prefs = dataStore.edit { prefs ->
            if (prefs[Keys.JELLYFIN_DEVICE_ID] == null) prefs[Keys.JELLYFIN_DEVICE_ID] = minted
        }
        return prefs[Keys.JELLYFIN_DEVICE_ID] ?: minted
    }

    /**
     * Converts a pre-token install's stored plaintext password into the
     * (salt, token) pair and deletes it, keeping the user signed in across
     * the upgrade without the password ever being written again.
     */
    private suspend fun migrateLegacyPassword() {
        val prefs = dataStore.data.first()
        val password = prefs[Keys.LEGACY_PASSWORD] ?: return
        val url = prefs[Keys.SERVER_URL]
        val user = prefs[Keys.USERNAME]
        if (url != null && user != null) {
            save(SubsonicCredentials.fromInput(url, user, password))
        } else {
            dataStore.edit { it.remove(Keys.LEGACY_PASSWORD) }
        }
    }

    /** Re-saves credentials stored in plaintext by pre-encryption installs. */
    private suspend fun encryptLegacyPlaintext() {
        val prefs = dataStore.data.first()
        val token = prefs[Keys.AUTH_TOKEN] ?: return
        if (cipher.isEncrypted(token)) return
        decode(prefs)?.let { save(it) }
    }

    /**
     * Removes everything tied to the signed-in account: credentials, the
     * resumption pointer, and the scrobble exclusion sets (both hold ids
     * minted by the old server). Device-wide state — [streamOriginal], the
     * scrobble master toggle, and the Plex/Jellyfin device ids — survives a
     * sign-out or server switch.
     */
    suspend fun clearAccount() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.PROVIDER)
            removeSubsonicAccount(prefs)
            removePlexAccount(prefs)
            removeJellyfinAccount(prefs)
            prefs.remove(Keys.LAST_MEDIA_ID)
            prefs.remove(Keys.LAST_POSITION_MS)
            prefs.remove(Keys.SCROBBLE_EXCLUDED_ARTISTS)
            prefs.remove(Keys.SCROBBLE_EXCLUDED_PLAYLISTS)
        }
    }

    private fun removeSubsonicAccount(prefs: MutablePreferences) {
        prefs.remove(Keys.SERVER_URL)
        prefs.remove(Keys.USERNAME)
        prefs.remove(Keys.AUTH_SALT)
        prefs.remove(Keys.AUTH_TOKEN)
        prefs.remove(Keys.LEGACY_PASSWORD)
    }

    /** Keeps [Keys.PLEX_CLIENT_ID] — the device identity outlives sign-ins. */
    private fun removePlexAccount(prefs: MutablePreferences) {
        prefs.remove(Keys.PLEX_TOKEN)
        prefs.remove(Keys.PLEX_SERVER_URI)
        prefs.remove(Keys.PLEX_SERVER_NAME)
        prefs.remove(Keys.PLEX_MACHINE_ID)
        prefs.remove(Keys.PLEX_SECTION_ID)
        prefs.remove(Keys.PLEX_USERNAME)
    }

    /** Keeps [Keys.JELLYFIN_DEVICE_ID] — the device identity outlives sign-ins. */
    private fun removeJellyfinAccount(prefs: MutablePreferences) {
        prefs.remove(Keys.JELLYFIN_TOKEN)
        prefs.remove(Keys.JELLYFIN_BASE_URL)
        prefs.remove(Keys.JELLYFIN_SERVER_NAME)
        prefs.remove(Keys.JELLYFIN_USER_ID)
        prefs.remove(Keys.JELLYFIN_USERNAME)
        prefs.remove(Keys.JELLYFIN_LIBRARY_ID)
    }

    // --- Playback quality ---

    /**
     * Stream original files (`format=raw`, bit-perfect and gapless) or let
     * the server's transcoding config decide (saves data, breaks gapless).
     * Defaults to original — self-hosted libraries expect gapless playback.
     */
    val streamOriginal: Flow<Boolean> =
        dataStore.data.map { it[Keys.STREAM_ORIGINAL] ?: DEFAULT_STREAM_ORIGINAL }

    suspend fun setStreamOriginal(enabled: Boolean) {
        dataStore.edit { it[Keys.STREAM_ORIGINAL] = enabled }
    }

    // --- Autoplay ---

    /** Keep playing similar music when the queue ends (see QueueRadioListener). */
    val autoplaySimilar: Flow<Boolean> =
        dataStore.data.map { it[Keys.AUTOPLAY_SIMILAR] ?: DEFAULT_AUTOPLAY_SIMILAR }

    suspend fun setAutoplaySimilar(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTOPLAY_SIMILAR] = enabled }
    }

    // --- Scrobbling ---

    /**
     * What playback reporting is allowed. The app only talks to the user's
     * own server (Subsonic `scrobble`, Plex timeline, Jellyfin sessions);
     * the server forwards plays to Last.fm/ListenBrainz. The APIs have no
     * "count internally but don't forward" variant, so suppressing a play
     * here also keeps it out of the server's play counts and history.
     */
    data class ScrobbleSettings(
        val enabled: Boolean,
        /** Plays of these artists are never reported, regardless of context. */
        val excludedArtistIds: Set<String>,
        /** Plays started from these playlists are never reported. */
        val excludedPlaylistIds: Set<String>,
    ) {
        companion object {
            /** Fresh-install behavior: report plays, exclude nothing. */
            val DEFAULT = ScrobbleSettings(enabled = true, emptySet(), emptySet())
        }
    }

    val scrobbleSettings: Flow<ScrobbleSettings> = dataStore.data.map { prefs ->
        ScrobbleSettings(
            enabled = prefs[Keys.SCROBBLE_ENABLED] ?: ScrobbleSettings.DEFAULT.enabled,
            excludedArtistIds = prefs[Keys.SCROBBLE_EXCLUDED_ARTISTS]
                ?: ScrobbleSettings.DEFAULT.excludedArtistIds,
            excludedPlaylistIds = prefs[Keys.SCROBBLE_EXCLUDED_PLAYLISTS]
                ?: ScrobbleSettings.DEFAULT.excludedPlaylistIds,
        )
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_ENABLED] = enabled }
    }

    suspend fun setArtistScrobbleExcluded(artistId: String, excluded: Boolean) {
        dataStore.edit { it.editSet(Keys.SCROBBLE_EXCLUDED_ARTISTS, artistId, excluded) }
    }

    suspend fun setPlaylistScrobbleExcluded(playlistId: String, excluded: Boolean) {
        dataStore.edit { it.editSet(Keys.SCROBBLE_EXCLUDED_PLAYLISTS, playlistId, excluded) }
    }

    private fun MutablePreferences.editSet(
        key: Preferences.Key<Set<String>>,
        value: String,
        add: Boolean,
    ) {
        val current = this[key] ?: emptySet()
        this[key] = if (add) current + value else current - value
    }

    // --- Playback resumption (see MediaSession.Callback.onPlaybackResumption) ---

    data class ResumptionState(val mediaId: String, val positionMs: Long)

    suspend fun saveResumptionState(mediaId: String, positionMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_MEDIA_ID] = mediaId
            prefs[Keys.LAST_POSITION_MS] = positionMs
        }
    }

    suspend fun resumptionState(): ResumptionState? {
        val prefs = dataStore.data.first()
        val mediaId = prefs[Keys.LAST_MEDIA_ID] ?: return null
        return ResumptionState(mediaId, prefs[Keys.LAST_POSITION_MS] ?: 0L)
    }

    companion object {
        /**
         * Value of [streamOriginal] before anything is written; shared with
         * UI-layer stateIn initials so the settings screen never flashes the
         * wrong state.
         */
        const val DEFAULT_STREAM_ORIGINAL = true

        /** Value of [autoplaySimilar] before anything is written. */
        const val DEFAULT_AUTOPLAY_SIMILAR = true

        private const val PROVIDER_SUBSONIC = "subsonic"
        private const val PROVIDER_PLEX = "plex"
        private const val PROVIDER_JELLYFIN = "jellyfin"
    }
}
