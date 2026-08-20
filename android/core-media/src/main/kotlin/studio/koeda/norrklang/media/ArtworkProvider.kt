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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.artwork.KnownCoverIds
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager

/**
 * Serves cover art to the car media hosts as `content://` URIs.
 *
 * The AAOS/Android Auto hosts only load artwork through a content resolver —
 * remote http(s) icon URIs are ignored. `content://<appId>.artwork/cover/<id>`
 * downloads the authenticated Subsonic `getCoverArt` response into the cache
 * once and hands out read-only file descriptors.
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
                )
            segments.size == 3 && segments[0] == ArtworkContract.PATH_HOME ->
                catalogMixFile(
                    context,
                    dependencies.catalogMixesSession(),
                    session,
                    accountDir,
                    kind = segments[1],
                    key = segments[2],
                )
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

    /** The composed image for a static home button (`home/<key>`). */
    private fun homeButtonFile(
        context: Context,
        repository: MusicRepository,
        randomMix: RandomMixSession,
        session: ProviderSession,
        accountDir: File,
        key: String,
    ): File {
        val tile = HomeTile.forKey(key)
            ?: throw FileNotFoundException("Unknown home button: $key")
        return composedTileFile(
            context,
            session,
            accountDir,
            cacheKey = "home-button/$key",
            iconRes = tile.iconRes,
            accentColor = tile.accentColor,
        ) { HomeButtonArtwork.coverIds(tile, repository, randomMix) }
    }

    /**
     * The composed image for a dynamic catalog mix tile (`home/<kind>/<key>`).
     * The key is resolved against the current mix snapshot — an unknown key
     * (no snapshot yet after a process restart, or a stale host URI) serves
     * the cached image when one exists, like any render failure.
     */
    private fun catalogMixFile(
        context: Context,
        catalogMixes: CatalogMixesSession,
        session: ProviderSession,
        accountDir: File,
        kind: String,
        key: String,
    ): File {
        val mixKind = CatalogMixKind.forPath(kind)
            ?: throw FileNotFoundException("Unknown mix kind: $kind")
        return composedTileFile(
            context,
            session,
            accountDir,
            cacheKey = "home-button/$kind/$key",
            iconRes = mixKind.iconRes,
            accentColor = mixKind.accentColor,
        ) {
            val urls = when (mixKind) {
                CatalogMixKind.GENRE ->
                    catalogMixes.currentGenreMixes()
                        .firstOrNull { it.name == key }?.artworkUrls
                CatalogMixKind.DECADE ->
                    catalogMixes.currentDecadeMixes()
                        .firstOrNull { it.startYear.toString() == key }?.artworkUrls
            } ?: throw FileNotFoundException("Unknown mix: $kind/$key")
            HomeButtonArtwork.coverIds(urls)
        }
    }

    /**
     * The composed tile image for [cacheKey], re-rendered once the cached
     * copy goes stale. Render failures fall back to a stale image when one
     * exists — a slightly old collage beats a blank tile.
     */
    private fun composedTileFile(
        context: Context,
        session: ProviderSession,
        accountDir: File,
        cacheKey: String,
        iconRes: Int,
        accentColor: Int,
        coverIds: suspend () -> List<String>,
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
                val covers = runBlocking {
                    withTimeout(HOME_BUTTON_RENDER_TIMEOUT_MS) {
                        coverIds().mapNotNull { id ->
                            // Blocking downloads never suspend — check
                            // the deadline between them.
                            ensureActive()
                            try {
                                coverFile(session, accountDir, id)
                            } catch (_: FileNotFoundException) {
                                null
                            }
                        }
                    }
                }
                HomeButtonArtwork.render(context, iconRes, accentColor, covers, file)
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
            // so the content type is the reliable success signal.
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
            ArtworkContract.PATH_HOME -> "image/png"
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
    }
}
