package studio.koeda.norrklang.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicPasswordAuth
import studio.koeda.norrklang.subsonic.SubsonicTokenAuth

/**
 * Persists the configured server + account in Preferences DataStore.
 *
 * For Subsonic, the fixed (salt, token) pair is stored rather than the
 * password (Subsonic accepts a reused salt, see [SubsonicTokenAuth]) — except
 * for servers that reject token auth, where the password itself is stored
 * ([SubsonicPasswordAuth]). Either secret is password-equivalent, so it is
 * encrypted at rest with an Android Keystore key ([CredentialCipher]); both
 * app manifests set `allowBackup=false` so nothing here ever leaves the
 * device in a backup.
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
        val ACCOUNT_REVISION = stringPreferencesKey("account_revision")

        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val AUTH_SALT = stringPreferencesKey("auth_salt")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")

        /**
         * Password-auth sign-ins only; mutually exclusive with the pair
         * above. Distinct from [LEGACY_PASSWORD], which triggers a migration.
         */
        val AUTH_PASSWORD = stringPreferencesKey("auth_password")

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
        /**
         * Legacy: pre-1.3 installs pinned one library here. Read once by
         * [migrateLegacyLibrarySelection] to seed [LIBRARIES_EXCLUDED], then removed.
         */
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
        /** Legacy pinned library, like [PLEX_SECTION_ID]. */
        val JELLYFIN_LIBRARY_ID = stringPreferencesKey("jellyfin_library_id")

        /** Library ids hidden from browsing; absent/empty means every library. */
        val LIBRARIES_EXCLUDED = stringSetPreferencesKey("libraries_excluded")
        val LAST_MEDIA_ID = stringPreferencesKey("last_media_id")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")

        /** Per-station listen counts, one `count|lastPlayedMs|stationId` entry each. */
        val RADIO_PLAY_STATS = stringSetPreferencesKey("radio_play_stats")

        /**
         * Pre-quality-tier builds stored a raw-vs-transcoded boolean here;
         * kept only as a read fallback for [QUALITY_WIFI]/[QUALITY_CELLULAR].
         */
        val STREAM_ORIGINAL = booleanPreferencesKey("stream_original")
        val QUALITY_WIFI = stringPreferencesKey("stream_quality_wifi")
        val QUALITY_CELLULAR = stringPreferencesKey("stream_quality_cellular")
        val AUTOPLAY_SIMILAR = booleanPreferencesKey("autoplay_similar")
        val SCROBBLE_ENABLED = booleanPreferencesKey("scrobble_enabled")
        val SCROBBLE_EXCLUDED_ARTISTS = stringSetPreferencesKey("scrobble_excluded_artists")
        val SCROBBLE_EXCLUDED_PLAYLISTS = stringSetPreferencesKey("scrobble_excluded_playlists")
        val SCROBBLE_EXCLUDED_LIBRARIES = stringSetPreferencesKey("scrobble_excluded_libraries")

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
        val auth = prefs[Keys.AUTH_PASSWORD]?.let { stored ->
            SubsonicPasswordAuth(cipher.decrypt(stored) ?: return null)
        } ?: run {
            val salt = prefs[Keys.AUTH_SALT]?.let(cipher::decrypt) ?: return null
            val token = prefs[Keys.AUTH_TOKEN]?.let(cipher::decrypt) ?: return null
            SubsonicTokenAuth(salt, token)
        }
        return SubsonicCredentials(url, user, auth)
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
        // null (undecryptable — Keystore key gone) means signed out.
        val token = prefs[Keys.PLEX_TOKEN]?.let(cipher::decrypt) ?: return null
        return PlexAccount(
            serverUri = uri,
            serverName = prefs[Keys.PLEX_SERVER_NAME] ?: uri,
            machineIdentifier = prefs[Keys.PLEX_MACHINE_ID] ?: "",
            token = token,
            username = prefs[Keys.PLEX_USERNAME] ?: "",
        )
    }

    private fun decodeJellyfin(prefs: Preferences): JellyfinAccount? {
        val baseUrl = prefs[Keys.JELLYFIN_BASE_URL] ?: return null
        val userId = prefs[Keys.JELLYFIN_USER_ID] ?: return null
        // null (undecryptable — Keystore key gone) means signed out.
        val token = prefs[Keys.JELLYFIN_TOKEN]?.let(cipher::decrypt) ?: return null
        return JellyfinAccount(
            baseUrl = baseUrl,
            serverName = prefs[Keys.JELLYFIN_SERVER_NAME] ?: baseUrl,
            userId = userId,
            username = prefs[Keys.JELLYFIN_USERNAME] ?: "",
            token = token,
        )
    }

    suspend fun save(credentials: SubsonicCredentials) {
        dataStore.edit { prefs ->
            if (prefs[Keys.SERVER_URL] != credentials.baseUrl ||
                prefs[Keys.USERNAME] != credentials.username
            ) {
                clearAccountPlayback(prefs)
            }
            prefs[Keys.ACCOUNT_REVISION] = UUID.randomUUID().toString()
            prefs[Keys.PROVIDER] = PROVIDER_SUBSONIC
            prefs[Keys.SERVER_URL] = credentials.baseUrl
            prefs[Keys.USERNAME] = credentials.username
            when (val auth = credentials.auth) {
                is SubsonicTokenAuth -> {
                    prefs[Keys.AUTH_SALT] = cipher.encrypt(auth.salt)
                    prefs[Keys.AUTH_TOKEN] = cipher.encrypt(auth.token)
                    prefs.remove(Keys.AUTH_PASSWORD)
                }
                is SubsonicPasswordAuth -> {
                    prefs[Keys.AUTH_PASSWORD] = cipher.encrypt(auth.password)
                    prefs.remove(Keys.AUTH_SALT)
                    prefs.remove(Keys.AUTH_TOKEN)
                }
            }
            prefs.remove(Keys.LEGACY_PASSWORD)
            removePlexAccount(prefs)
            removeJellyfinAccount(prefs)
        }
    }

    /**
     * Persists the account and, in the same write, the sign-in picker's
     * library selection — written after [clearAccountPlayback] so a new
     * server's choice survives the account switch.
     */
    suspend fun savePlex(account: PlexAccount, excludedLibraryIds: Set<String> = emptySet()) {
        dataStore.edit { prefs ->
            if (prefs[Keys.PLEX_SERVER_URI] != account.serverUri ||
                prefs[Keys.PLEX_USERNAME] != account.username
            ) {
                clearAccountPlayback(prefs)
            }
            prefs[Keys.ACCOUNT_REVISION] = UUID.randomUUID().toString()
            prefs[Keys.PROVIDER] = PROVIDER_PLEX
            prefs[Keys.PLEX_TOKEN] = cipher.encrypt(account.token)
            prefs[Keys.PLEX_SERVER_URI] = account.serverUri
            prefs[Keys.PLEX_SERVER_NAME] = account.serverName
            prefs[Keys.PLEX_MACHINE_ID] = account.machineIdentifier
            prefs[Keys.PLEX_USERNAME] = account.username
            prefs.remove(Keys.PLEX_SECTION_ID)
            prefs.putSet(Keys.LIBRARIES_EXCLUDED, excludedLibraryIds)
            removeSubsonicAccount(prefs)
            removeJellyfinAccount(prefs)
        }
    }

    /** See [savePlex]. */
    suspend fun saveJellyfin(account: JellyfinAccount, excludedLibraryIds: Set<String> = emptySet()) {
        dataStore.edit { prefs ->
            if (prefs[Keys.JELLYFIN_BASE_URL] != account.baseUrl ||
                prefs[Keys.JELLYFIN_USER_ID] != account.userId
            ) {
                clearAccountPlayback(prefs)
            }
            prefs[Keys.ACCOUNT_REVISION] = UUID.randomUUID().toString()
            prefs[Keys.PROVIDER] = PROVIDER_JELLYFIN
            prefs[Keys.JELLYFIN_TOKEN] = cipher.encrypt(account.token)
            prefs[Keys.JELLYFIN_BASE_URL] = account.baseUrl
            prefs[Keys.JELLYFIN_SERVER_NAME] = account.serverName
            prefs[Keys.JELLYFIN_USER_ID] = account.userId
            prefs[Keys.JELLYFIN_USERNAME] = account.username
            prefs.remove(Keys.JELLYFIN_LIBRARY_ID)
            prefs.putSet(Keys.LIBRARIES_EXCLUDED, excludedLibraryIds)
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
     * resumption pointer, the library selection and the scrobble exclusion
     * sets (all hold ids minted by the old server). Device-wide state — the
     * quality tiers, the scrobble master toggle, and the Plex/Jellyfin device
     * ids — survives a sign-out or server switch.
     */
    suspend fun clearAccount() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.PROVIDER)
            prefs.remove(Keys.ACCOUNT_REVISION)
            removeSubsonicAccount(prefs)
            removePlexAccount(prefs)
            removeJellyfinAccount(prefs)
            clearAccountPlayback(prefs)
        }
    }

    private fun clearAccountPlayback(prefs: MutablePreferences) {
        prefs.remove(Keys.LAST_MEDIA_ID)
        prefs.remove(Keys.LAST_POSITION_MS)
        prefs.remove(Keys.RADIO_PLAY_STATS)
        prefs.remove(Keys.SCROBBLE_EXCLUDED_ARTISTS)
        prefs.remove(Keys.SCROBBLE_EXCLUDED_PLAYLISTS)
        prefs.remove(Keys.SCROBBLE_EXCLUDED_LIBRARIES)
        prefs.remove(Keys.LIBRARIES_EXCLUDED)
    }

    /** Mint a revision for older installs, without changing their saved queue. */
    suspend fun accountRevision(): String? = dataStore.edit { prefs ->
        val signedIn = prefs[Keys.SERVER_URL] != null || prefs[Keys.PLEX_TOKEN] != null ||
            prefs[Keys.JELLYFIN_TOKEN] != null
        if (signedIn && prefs[Keys.ACCOUNT_REVISION] == null) {
            prefs[Keys.ACCOUNT_REVISION] = UUID.randomUUID().toString()
        }
    }[Keys.ACCOUNT_REVISION]

    private fun removeSubsonicAccount(prefs: MutablePreferences) {
        prefs.remove(Keys.SERVER_URL)
        prefs.remove(Keys.USERNAME)
        prefs.remove(Keys.AUTH_SALT)
        prefs.remove(Keys.AUTH_TOKEN)
        prefs.remove(Keys.AUTH_PASSWORD)
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
     * Quality tier per network type, applied by the player's stream URL
     * resolver when a track's data source first loads. Wi-Fi defaults to original (bit-perfect,
     * gapless); cellular defaults to capped — see
     * [StreamQuality.DEFAULT_CELLULAR] for why.
     */
    val streamQualityWifi: Flow<StreamQuality> =
        dataStore.data.map {
            decodeQuality(it, Keys.QUALITY_WIFI, StreamQuality.DEFAULT_WIFI)
        }

    val streamQualityCellular: Flow<StreamQuality> =
        dataStore.data.map {
            decodeQuality(it, Keys.QUALITY_CELLULAR, StreamQuality.DEFAULT_CELLULAR)
        }

    suspend fun setStreamQualityWifi(quality: StreamQuality) {
        dataStore.edit { it[Keys.QUALITY_WIFI] = quality.storageValue }
    }

    suspend fun setStreamQualityCellular(quality: StreamQuality) {
        dataStore.edit { it[Keys.QUALITY_CELLULAR] = quality.storageValue }
    }

    /**
     * Legacy fallback: a user who had turned "stream original" OFF had opted
     * into server transcoding, so they land on the highest capped tier
     * (on both networks) instead of silently returning to original quality.
     */
    private fun decodeQuality(
        prefs: Preferences,
        key: Preferences.Key<String>,
        default: StreamQuality,
    ): StreamQuality =
        prefs[key]?.let(StreamQuality::fromStorageValue)
            ?: if (prefs[Keys.STREAM_ORIGINAL] == false) StreamQuality.HIGH else default

    // --- Autoplay ---

    /** Keep playing similar music when the queue ends (see QueueRadioListener). */
    val autoplaySimilar: Flow<Boolean> =
        dataStore.data.map { it[Keys.AUTOPLAY_SIMILAR] ?: DEFAULT_AUTOPLAY_SIMILAR }

    suspend fun setAutoplaySimilar(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTOPLAY_SIMILAR] = enabled }
    }

    // --- Libraries ---

    /**
     * Server library ids hidden from browsing, search and mixes (see
     * LibraryScope). Empty = every library; new server libraries show up
     * automatically. Account-scoped.
     */
    val excludedLibraryIds: Flow<Set<String>> =
        dataStore.data.map { it[Keys.LIBRARIES_EXCLUDED] ?: emptySet() }

    suspend fun setLibraryExcluded(libraryId: String, excluded: Boolean) {
        dataStore.edit { it.editSet(Keys.LIBRARIES_EXCLUDED, libraryId, excluded) }
    }

    /**
     * One-shot upgrade seed: pre-1.3 Plex/Jellyfin installs browsed exactly
     * one library, so every OTHER library in [availableIds] is excluded to
     * keep showing the same content, then the legacy key is dropped. A
     * legacy id no longer on the server just drops the key (all libraries).
     */
    suspend fun migrateLegacyLibrarySelection(availableIds: Collection<String>) {
        val snapshot = dataStore.data.first()
        val legacyKey = when (snapshot[Keys.PROVIDER]) {
            PROVIDER_PLEX -> Keys.PLEX_SECTION_ID
            PROVIDER_JELLYFIN -> Keys.JELLYFIN_LIBRARY_ID
            else -> return
        }
        if (snapshot[legacyKey] == null) return
        dataStore.edit { prefs ->
            val legacy = prefs[legacyKey] ?: return@edit
            if (legacy in availableIds && prefs[Keys.LIBRARIES_EXCLUDED] == null) {
                prefs.putSet(Keys.LIBRARIES_EXCLUDED, availableIds.filterTo(mutableSetOf()) { it != legacy })
            }
            prefs.remove(legacyKey)
        }
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
        /** Plays of tracks in these server libraries are never reported. */
        val excludedLibraryIds: Set<String> = emptySet(),
    ) {
        companion object {
            /** Fresh-install behavior: report plays, exclude nothing. */
            val DEFAULT = ScrobbleSettings(enabled = true, emptySet(), emptySet(), emptySet())
        }
    }

    val scrobbleSettings: Flow<ScrobbleSettings> = dataStore.data.map { prefs ->
        ScrobbleSettings(
            enabled = prefs[Keys.SCROBBLE_ENABLED] ?: ScrobbleSettings.DEFAULT.enabled,
            excludedArtistIds = prefs[Keys.SCROBBLE_EXCLUDED_ARTISTS]
                ?: ScrobbleSettings.DEFAULT.excludedArtistIds,
            excludedPlaylistIds = prefs[Keys.SCROBBLE_EXCLUDED_PLAYLISTS]
                ?: ScrobbleSettings.DEFAULT.excludedPlaylistIds,
            excludedLibraryIds = prefs[Keys.SCROBBLE_EXCLUDED_LIBRARIES]
                ?: ScrobbleSettings.DEFAULT.excludedLibraryIds,
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

    suspend fun setLibraryScrobbleExcluded(libraryId: String, excluded: Boolean) {
        dataStore.edit { it.editSet(Keys.SCROBBLE_EXCLUDED_LIBRARIES, libraryId, excluded) }
    }

    private fun MutablePreferences.putSet(key: Preferences.Key<Set<String>>, value: Set<String>) {
        if (value.isEmpty()) remove(key) else this[key] = value
    }

    private fun MutablePreferences.editSet(
        key: Preferences.Key<Set<String>>,
        value: String,
        add: Boolean,
    ) {
        val current = this[key] ?: emptySet()
        this[key] = if (add) current + value else current - value
    }

    // --- Internet radio ---

    /** Local listening history for one station — the server keeps no radio play counts. */
    data class RadioPlayStats(val stationId: String, val playCount: Int, val lastPlayedMs: Long)

    /** Every station listened to on this account, most listened first (ties: most recent). */
    val radioPlayStats: Flow<List<RadioPlayStats>> =
        dataStore.data.map { decodeRadioStats(it[Keys.RADIO_PLAY_STATS]) }

    /** Counts one listen of [stationId]; the history is capped at [MAX_RADIO_STATS] stations. */
    suspend fun recordRadioPlay(stationId: String, nowMs: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val stats = decodeRadioStats(prefs[Keys.RADIO_PLAY_STATS])
                .associateByTo(LinkedHashMap()) { it.stationId }
            val count = (stats[stationId]?.playCount ?: 0) + 1
            stats[stationId] = RadioPlayStats(stationId, count, nowMs)
            prefs[Keys.RADIO_PLAY_STATS] = stats.values
                .sortedWith(radioStatsOrder)
                .take(MAX_RADIO_STATS)
                .mapTo(HashSet()) { "${it.playCount}|${it.lastPlayedMs}|${it.stationId}" }
        }
    }

    private val radioStatsOrder =
        compareByDescending<RadioPlayStats> { it.playCount }.thenByDescending { it.lastPlayedMs }

    // The id comes last so a '|' inside it survives the split.
    private fun decodeRadioStats(entries: Set<String>?): List<RadioPlayStats> =
        entries.orEmpty().mapNotNull { entry ->
            val parts = entry.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val count = parts[0].toIntOrNull() ?: return@mapNotNull null
            val lastPlayed = parts[1].toLongOrNull() ?: return@mapNotNull null
            RadioPlayStats(parts[2], count, lastPlayed)
        }.sortedWith(radioStatsOrder)

    // --- Playback resumption (see MediaSession.Callback.onPlaybackResumption) ---

    data class ResumptionState(val mediaId: String, val positionMs: Long)

    suspend fun saveResumptionState(
        mediaId: String,
        positionMs: Long,
        expectedRevision: String? = null,
    ) {
        dataStore.edit { prefs ->
            if (expectedRevision != null && prefs[Keys.ACCOUNT_REVISION] != expectedRevision) return@edit
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
        /** Value of [autoplaySimilar] before anything is written. */
        const val DEFAULT_AUTOPLAY_SIMILAR = true

        /** Stations kept in [radioPlayStats]; the least listened drop off beyond it. */
        const val MAX_RADIO_STATS = 50

        private const val PROVIDER_SUBSONIC = "subsonic"
        private const val PROVIDER_PLEX = "plex"
        private const val PROVIDER_JELLYFIN = "jellyfin"
    }
}
