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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.artwork.KnownCoverIds
import studio.koeda.norrklang.data.repo.MusicRepository
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
 *  - cache files live in a per-account directory
 *    ([SubsonicCredentials.cacheFingerprint]); other accounts' directories
 *    are purged, so a later sign-in to a server reusing cover ids can never
 *    receive the previous account's images
 *
 * Only public cover bytes are served; the authenticated server URL stays
 * in-process — never logged or echoed in error messages.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun sessionManager(): SessionManager
        fun musicRepository(): MusicRepository
        fun randomMixSession(): RandomMixSession
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("Provider not attached")
        val dependencies = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
        val session = dependencies.sessionManager().connectedOrNull()
            ?: throw FileNotFoundException("Not signed in")
        val accountDir = accountCacheDir(context, session.credentials.cacheFingerprint)

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
            else -> throw FileNotFoundException("Unsupported artwork uri: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The cached cover file for [coverArtId], downloading it on first use. */
    private fun coverFile(
        session: SessionManager.SessionState.Connected,
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
            download(session, coverArtId, file)
            evict(accountDir)
        }
        return file
    }

    /**
     * The composed home-button image for [key], re-rendered once the cached
     * copy goes stale. Render failures fall back to a stale image when one
     * exists — a slightly old collage beats a blank tile.
     */
    private fun homeButtonFile(
        context: Context,
        repository: MusicRepository,
        randomMix: RandomMixSession,
        session: SessionManager.SessionState.Connected,
        accountDir: File,
        key: String,
    ): File {
        val tile = HomeTile.forKey(key)
            ?: throw FileNotFoundException("Unknown home button: $key")
        val file = File(accountDir, hashedFileName("home-button/$key"))
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
                        HomeButtonArtwork.coverIds(tile, repository, randomMix)
                            .mapNotNull { id ->
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
                HomeButtonArtwork.render(context, tile, covers, file)
                evict(accountDir)
            } catch (e: Exception) {
                if (file.length() == 0L) {
                    throw FileNotFoundException("Could not render home button $key").apply {
                        initCause(e)
                    }
                }
            }
        }
        return file
    }

    private fun download(
        session: SessionManager.SessionState.Connected,
        coverArtId: String,
        target: File,
    ) {
        val connection =
            URL(session.urlBuilder.coverArtUrl(coverArtId)).openConnection() as HttpURLConnection
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
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_CACHE_FILES = 512
        const val MAX_CACHE_BYTES = 128L * 1024 * 1024

        // Matches the repository's TTL cache, so a fresh browse after a
        // library change picks up a re-rendered image.
        const val HOME_BUTTON_TTL_MS = 5L * 60 * 1000

        // Fits a full cold render (up to 12 covers) on healthy LTE; far below
        // the worst-case sum of per-download timeouts a degenerate server
        // could otherwise pin a binder thread for.
        const val HOME_BUTTON_RENDER_TIMEOUT_MS = 20_000L
    }
}
