package studio.koeda.norrklang.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import studio.koeda.norrklang.data.di.ApplicationScope

/**
 * Last-known browse data on disk, one JSON file per (account, list, key), so
 * a cold start can show something before the server answers.
 *
 * Lives under cacheDir: the system may delete it at any time, and every
 * failure to read is a miss, never an error. Only the current account's
 * folder survives; resolving it deletes the others, which is the purge for
 * account switches (ArtworkProvider does the same for covers).
 */
@Singleton
class SnapshotStore(
    private val root: File,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
) {

    @Inject
    constructor(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope) :
        this(File(context.cacheDir, "browse"), scope)

    @Serializable
    private class Envelope<T>(val v: Int, val key: String, val data: T)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Bumped by [purge] so a write queued before a sign-out is skipped. */
    private val generation = AtomicLong()

    /** The account whose siblings were already purged; guarded by [mutex]. */
    private var resolvedFingerprint: String? = null

    /** The stored value, or null when absent, unreadable or written by another schema version. */
    suspend fun <T> read(
        fingerprint: String,
        name: String,
        key: String,
        serializer: KSerializer<T>,
    ): T? = locked {
        val file = file(fingerprint, name, key)
        if (!file.exists()) return@locked null
        try {
            val envelope = json.decodeFromString(Envelope.serializer(serializer), file.readText())
            if (envelope.v == VERSION && envelope.key == key) envelope.data else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // SerializationException included: a truncated or outdated file.
            null
        }.also { if (it == null) file.delete() }
    }

    /** Queues the write and returns at once; a failed write only costs the next cold start. */
    fun <T> write(
        fingerprint: String,
        name: String,
        key: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        val queuedIn = generation.get()
        scope.launch {
            locked {
                if (generation.get() != queuedIn) return@locked
                try {
                    val text = json.encodeToString(
                        Envelope.serializer(serializer),
                        Envelope(VERSION, key, value),
                    )
                    if (text.length > MAX_ENTRY_CHARS) return@locked
                    val target = file(fingerprint, name, key)
                    val tmp = File(target.path + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(target)) tmp.delete()
                    evict(target.parentFile)
                } catch (_: IOException) {
                } catch (_: SerializationException) {
                }
            }
        }
    }

    /** Deletes every stored key of the lists called [names]. */
    suspend fun drop(fingerprint: String, names: Set<String>) = locked {
        accountDir(fingerprint).listFiles()?.forEach { file ->
            if (file.name.substringBeforeLast('-') in names) file.delete()
        }
    }

    /** Deletes everything, for sign-out. */
    suspend fun purge() {
        generation.incrementAndGet()
        locked {
            root.deleteRecursively()
            resolvedFingerprint = null
        }
    }

    private suspend fun <R> locked(block: () -> R): R =
        withContext(io) { mutex.withLock { block() } }

    private fun file(fingerprint: String, name: String, key: String): File =
        File(accountDir(fingerprint), "$name-${hash(key)}.json")

    private fun accountDir(fingerprint: String): File {
        if (resolvedFingerprint != fingerprint) {
            root.listFiles()?.forEach { if (it.name != fingerprint) it.deleteRecursively() }
            resolvedFingerprint = fingerprint
        }
        return File(root, fingerprint).apply { mkdirs() }
    }

    private fun evict(dir: File?) {
        val files = dir?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (count <= MAX_FILES && bytes <= MAX_BYTES) break
            bytes -= file.length()
            count--
            file.delete()
        }
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Bump when a stored model changes meaning rather than shape. */
        const val VERSION = 1
        const val MAX_ENTRY_CHARS = 4 * 1024 * 1024
        const val MAX_FILES = 48
        const val MAX_BYTES = 24L * 1024 * 1024
    }
}
