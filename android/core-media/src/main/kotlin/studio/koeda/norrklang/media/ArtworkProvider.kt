package studio.koeda.norrklang.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.artwork.KnownCoverIds
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager

/**
 * Serves cover art to the car media hosts as `content://` URIs.
 *
 * The AAOS/Android Auto hosts only load artwork through a content resolver —
 * remote http(s) icon URIs are ignored. `content://<appId>.artwork/cover/<id>`
 * downloads the provider's authenticated artwork response into the cache
 * once and hands out read-only file descriptors. `station/<id>` composes a
 * radio station's tile from the server's image or the station homepage's
 * icon ([StationLogo]) — the only non-server host ever contacted.
 *
 * Exported for the (separate-process) hosts, so hardened against any caller:
 *  - network fetches only for ids the app itself handed out ([KnownCoverIds]);
 *    already-cached files are served regardless
 *  - downloads capped at [MAX_IMAGE_BYTES]; cache bounded
 *    ([MAX_CACHE_FILES]/[MAX_CACHE_BYTES], oldest-first eviction)
 *  - concurrent downloads capped at [MAX_CONCURRENT_DOWNLOADS] — openFile
 *    runs on the process's small shared binder-thread pool, and a fast
 *    scroll through a large uncached library must not pin every binder
 *    thread on slow server responses (the system kills a process whose
 *    provider stops answering)
 *  - cache files live in a per-account directory
 *    ([ProviderSession.cacheFingerprint]); other accounts' directories
 *    are purged, so a later sign-in to a server reusing cover ids can never
 *    receive the previous account's images
 *
 * Only public cover bytes are served; the authenticated server URL stays
 * in-process — never logged or echoed in error messages.
 */
class ArtworkProvider : ContentProvider() {

    /**
     * Bounds concurrent network fetches. Fair, so requests for the items the
     * user settled on are served in arrival order after a scroll burst.
     */
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS, true)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun sessionManager(): SessionManager
        fun musicRepository(): MusicRepository
        fun randomMixSession(): RandomMixSession
        fun catalogMixesSession(): CatalogMixesSession
        fun bestOfMixesSession(): BestOfMixesSession
        fun similarMixesSession(): SimilarMixesSession
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("Provider not attached")
        val dependencies = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
        val session = dependencies.sessionManager().connectedOrNull()?.session
            ?: throw FileNotFoundException("Not signed in")
        val accountDir = accountCacheDir(context, session.cacheFingerprint)

        val segments = uri.pathSegments
        val file = when {
            segments.size == 2 && segments[0] == ArtworkContract.PATH_COVER ->
                coverFile(session, accountDir, segments[1])
            segments.size == 2 && segments[0] == ArtworkContract.PATH_HOME ->
                homeButtonFile(
                    context,
                    dependencies.musicRepository(),
                    dependencies.randomMixSession(),
                    session,
                    accountDir,
                    segments[1],
                    uri,
                )
            segments.size == 3 && segments[0] == ArtworkContract.PATH_HOME ->
                homeMixFile(
                    context,
                    dependencies,
                    session,
                    accountDir,
                    kind = segments[1],
                    key = segments[2],
                    uri = uri,
                )
            segments.size == 2 && segments[0] == ArtworkContract.PATH_STATION ->
                stationFile(context, dependencies.musicRepository(), session, accountDir, segments[1])
            else -> throw FileNotFoundException("Unsupported artwork uri: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The cached cover file for [coverArtId], downloading it on first use. */
    private fun coverFile(
        session: ProviderSession,
        accountDir: File,
        coverArtId: String,
    ): File {
        val file = File(accountDir, hashedFileName(coverArtId))
        if (file.length() == 0L) {
            if (coverArtId !in KnownCoverIds) {
                // Exported provider: don't let arbitrary callers trigger
                // authenticated fetches for ids we never referenced.
                throw FileNotFoundException("Unknown cover id")
            }
            withDownloadSlot {
                // Re-check under the slot: a concurrent request for the same
                // id may have finished the download while this one waited.
                if (file.length() == 0L) download(session, coverArtId, file)
            }
            evict(accountDir)
        }
        return file
    }

    /**
     * Runs [block] holding one of the [MAX_CONCURRENT_DOWNLOADS] download
     * slots. When none frees up within [DOWNLOAD_SLOT_WAIT_MS] the request
     * fails fast instead of pinning its binder thread — the host shows a
     * placeholder and re-requests the URI when the item is next bound
     * (typically once scrolling settles).
     */
    private fun <T> withDownloadSlot(block: () -> T): T {
        val acquired = try {
            downloadSlots.tryAcquire(DOWNLOAD_SLOT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) throw FileNotFoundException("Artwork downloads saturated")
        try {
            return block()
        } finally {
            downloadSlots.release()
        }
    }

    /** The library-selection version in the URI: a stale render is never served across a change. */
    private fun tileVersion(uri: Uri): String =
        uri.getQueryParameter(ArtworkContract.PARAM_VERSION).orEmpty()

    /** The composed image for a static home button (`home/<key>`). */
    private fun homeButtonFile(
        context: Context,
        repository: MusicRepository,
        randomMix: RandomMixSession,
        session: ProviderSession,
        accountDir: File,
        key: String,
        uri: Uri,
    ): File {
        val tile = HomeTile.forKey(key)
            ?: throw FileNotFoundException("Unknown home button: $key")
        return composedTileFile(
            context,
            accountDir,
            cacheKey = "home-button/${tileVersion(uri)}/$key",
            iconRes = tile.iconRes,
        ) { coverFiles(session, accountDir, HomeButtonArtwork.coverIds(tile, repository, randomMix)) }
    }

    /**
     * The composed image for a dynamic mix tile (`home/<kind>/<key>`).
     * The key is resolved against the owning session's current snapshot — an
     * unknown key (no snapshot yet after a process restart, or a stale host
     * URI) serves the cached image when one exists, like any render failure.
     * The artist mixes declare one cover (the seed artist's image), so they
     * render full-bleed under the badge rather than as a collage.
     */
    private fun homeMixFile(
        context: Context,
        dependencies: Dependencies,
        session: ProviderSession,
        accountDir: File,
        kind: String,
        key: String,
        uri: Uri,
    ): File {
        val mixKind = HomeMixKind.forPath(kind)
            ?: throw FileNotFoundException("Unknown mix kind: $kind")
        return composedTileFile(
            context,
            accountDir,
            cacheKey = "home-button/${tileVersion(uri)}/$kind/$key",
            iconRes = mixKind.iconRes,
        ) {
            val urls = when (mixKind) {
                HomeMixKind.BEST_OF ->
                    dependencies.bestOfMixesSession().currentMixes()
                        .firstOrNull { it.artist.id == key }
                        ?.let { listOfNotNull(it.artworkUrl) }
                HomeMixKind.SIMILAR ->
                    dependencies.similarMixesSession().currentMixes()
                        .firstOrNull { it.artist.id == key }
                        ?.let { listOfNotNull(it.artworkUrl) }
                HomeMixKind.GENRE ->
                    dependencies.catalogMixesSession().currentGenreMixes()
                        .firstOrNull { it.name == key }?.artworkUrls
                HomeMixKind.DECADE ->
                    dependencies.catalogMixesSession().currentDecadeMixes()
                        .firstOrNull { it.startYear.toString() == key }?.artworkUrls
            } ?: throw FileNotFoundException("Unknown mix: $kind/$key")
            coverFiles(session, accountDir, HomeButtonArtwork.coverIds(urls))
        }
    }

    /**
     * A radio station's tile (`station/<id>`): the server's station image
     * when it has one, else the homepage icon, full-bleed under the radio
     * badge; the plain glyph when neither exists.
     */
    private fun stationFile(
        context: Context,
        repository: MusicRepository,
        session: ProviderSession,
        accountDir: File,
        stationId: String,
    ): File = composedTileFile(
        context,
        accountDir,
        cacheKey = "station/$stationId",
        iconRes = HomeTile.RADIO.iconRes,
    ) { listOfNotNull(stationLogo(session, accountDir, repository.radioStation(stationId))) }

    /**
     * The station's logo file, or null. A homepage without a usable icon is
     * remembered for [STATION_LOGO_RETRY_MS] so browsing doesn't re-scrape it.
     */
    private fun stationLogo(session: ProviderSession, accountDir: File, station: RadioStation): File? {
        station.artworkUrl?.let { url ->
            val id = HomeButtonArtwork.coverIds(listOf(url)).firstOrNull() ?: return null
            return try {
                coverFile(session, accountDir, id)
            } catch (_: FileNotFoundException) {
                null
            }
        }
        val homePage = station.homePageUrl?.takeIf { it.startsWith("http") } ?: return null
        val logo = File(accountDir, hashedFileName("station-logo/${station.id}"))
        if (logo.length() > 0) return logo
        val miss = File(accountDir, hashedFileName("station-logo-miss/${station.id}"))
        if (System.currentTimeMillis() - miss.lastModified() < STATION_LOGO_RETRY_MS) return null
        val found = withDownloadSlot { StationLogo.fetch(homePage, logo) }
        if (!found) {
            miss.writeText("")
            return null
        }
        evict(accountDir)
        return logo
    }

    /** The cached files for [ids], skipping covers that can't be fetched. */
    private suspend fun coverFiles(
        session: ProviderSession,
        accountDir: File,
        ids: List<String>,
    ): List<File> = ids.mapNotNull { id ->
        // Blocking downloads never suspend — check the deadline between them.
        currentCoroutineContext().ensureActive()
        try {
            coverFile(session, accountDir, id)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * The composed tile image for [cacheKey], re-rendered once the cached
     * copy goes stale. Render failures fall back to a stale image when one
     * exists — a slightly old collage beats a blank tile.
     */
    private fun composedTileFile(
        context: Context,
        accountDir: File,
        cacheKey: String,
        iconRes: Int,
        covers: suspend () -> List<File>,
    ): File {
        val file = File(accountDir, hashedFileName(cacheKey))
        val stale = file.length() == 0L ||
            System.currentTimeMillis() - file.lastModified() > HOME_BUTTON_TTL_MS
        if (stale) {
            try {
                // openFile runs on a small shared binder-thread pool; cap the
                // aggregate work so a slow server can't pin a thread for up to
                // 12 sequential downloads' worth of timeouts. On timeout the
                // catch below serves the stale image like any render failure.
                val coverFiles = runBlocking {
                    withTimeout(HOME_BUTTON_RENDER_TIMEOUT_MS) { covers() }
                }
                HomeButtonArtwork.render(context, iconRes, coverFiles, file)
                evict(accountDir)
            } catch (e: Exception) {
                if (file.length() == 0L) {
                    throw FileNotFoundException("Could not render tile $cacheKey").apply {
                        initCause(e)
                    }
                }
            }
        }
        return file
    }

    private fun download(
        session: ProviderSession,
        coverArtId: String,
        target: File,
    ) {
        val connection =
            URL(session.artworkUrl(coverArtId)).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            // Subsonic reports errors (bad id, auth) as a 200 with a JSON body,
            // so the content type is the reliable success signal — and the
            // right check for the other providers too.
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentType?.startsWith("image/") != true
            ) {
                throw FileNotFoundException("No artwork for $coverArtId")
            }
            val tmp = File.createTempFile("art-", ".part", target.parentFile)
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> copyBounded(input, output) }
                }
                // Concurrent fetches of the same id race benignly: last rename wins.
                if (!tmp.renameTo(target)) {
                    throw FileNotFoundException("Could not cache artwork for $coverArtId")
                }
            } finally {
                tmp.delete()
            }
        } catch (e: IOException) {
            throw FileNotFoundException("Fetching artwork for $coverArtId failed").apply {
                initCause(e)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Copies at most [MAX_IMAGE_BYTES]; anything larger is rejected. */
    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_IMAGE_BYTES) throw IOException("Artwork exceeds size limit")
            output.write(buffer, 0, read)
        }
    }

    /**
     * The current account's cache directory. Other accounts' directories are
     * deleted on the way — the sign-out/account-switch purge, with no
     * SessionManager hook needed.
     */
    private fun accountCacheDir(context: Context, fingerprint: String): File {
        val root = File(context.cacheDir, CACHE_DIR)
        root.listFiles()?.forEach { child ->
            if (child.name != fingerprint) child.deleteRecursively()
        }
        return File(root, fingerprint).apply { mkdirs() }
    }

    /**
     * coverArt ids are server-controlled input on an exported provider —
     * hash them instead of using them as path components.
     */
    private fun hashedFileName(coverArtId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(coverArtId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Deletes oldest files first until the cache is back under its bounds. */
    private fun evict(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        if (count <= MAX_CACHE_FILES && bytes <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (count <= MAX_CACHE_FILES && bytes <= MAX_CACHE_BYTES) break
            bytes -= file.length()
            count--
            file.delete()
        }
    }

    override fun getType(uri: Uri): String? =
        when (uri.pathSegments.firstOrNull()) {
            ArtworkContract.PATH_COVER -> "image/*"
            ArtworkContract.PATH_HOME, ArtworkContract.PATH_STATION -> "image/png"
            else -> null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val CACHE_DIR = "artwork"

        // Tighter than typical API timeouts on purpose: artwork is
        // best-effort decoration, and every download holds a scarce
        // download slot (and its binder thread) for its full duration.
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000

        /**
         * Concurrent network fetches. The binder pool is ~15 threads; even
         * fully saturated, artwork leaves most of them free for browse and
         * playback traffic.
         */
        const val MAX_CONCURRENT_DOWNLOADS = 4

        /**
         * Longest a saturated request waits for a slot. Long enough to ride
         * out a burst once scrolling settles; short enough that even a pile-up
         * across the whole binder pool stays far below the system's
         * unresponsive-provider threshold.
         */
        const val DOWNLOAD_SLOT_WAIT_MS = 2_000L

        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        // 512px covers run ~30–100 KB; sized so a several-thousand-album
        // library's covers stay resident instead of eviction-thrashing (and
        // re-downloading) on every pass through the grid.
        const val MAX_CACHE_FILES = 4096
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024

        // Matches the repository's TTL cache, so a fresh browse after a
        // library change picks up a re-rendered image.
        const val HOME_BUTTON_TTL_MS = 5L * 60 * 1000

        // Fits a full cold render (up to 12 covers) on healthy LTE; far below
        // the worst-case sum of per-download timeouts a degenerate server
        // could otherwise pin a binder thread for.
        const val HOME_BUTTON_RENDER_TIMEOUT_MS = 20_000L

        /** How long a homepage known to have no usable icon is left alone. */
        const val STATION_LOGO_RETRY_MS = 24L * 60 * 60 * 1000
    }
}
