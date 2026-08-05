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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val AUTH_SALT = stringPreferencesKey("auth_salt")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val LAST_MEDIA_ID = stringPreferencesKey("last_media_id")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val STREAM_ORIGINAL = booleanPreferencesKey("stream_original")
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
            migrateLegacyPassword()
            encryptLegacyPlaintext()
        }
        return credentials.first()
    }

    suspend fun save(credentials: SubsonicCredentials) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = credentials.baseUrl
            prefs[Keys.USERNAME] = credentials.username
            prefs[Keys.AUTH_SALT] = cipher.encrypt(credentials.auth.salt)
            prefs[Keys.AUTH_TOKEN] = cipher.encrypt(credentials.auth.token)
            prefs.remove(Keys.LEGACY_PASSWORD)
        }
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

    suspend fun clear() {
        dataStore.edit { it.clear() }
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

    // --- Scrobbling ---

    /**
     * What playback reporting is allowed. The app only talks to the user's
     * own server (Subsonic `scrobble`); the server forwards plays to
     * Last.fm/ListenBrainz. The API has no "count internally but don't
     * forward" variant, so suppressing a play here also keeps it out of the
     * server's play counts and history.
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
    }
}
