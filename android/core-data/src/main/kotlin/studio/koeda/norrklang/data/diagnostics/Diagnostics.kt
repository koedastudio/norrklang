package studio.koeda.norrklang.data.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device failure log for hardware we can never attach a debugger to: the
 * last crash plus a short ring of recent handled errors, persisted under
 * filesDir and rendered on the settings screen so a user can read the actual
 * failure back to us from the driver's seat. Play vitals needs a diagnostics
 * opt-in most car users never gave — this is the only field telemetry we get.
 *
 * A plain object rather than an injected type: it must be reachable from
 * CoroutineExceptionHandlers and the crash handler before any DI component
 * exists, and it must never itself become a failure source — every entry
 * point swallows its own errors.
 *
 * SECURITY: entries hold exception class + message only. Never record
 * stream/cover URLs or anything else carrying the auth token.
 */
object Diagnostics {

    private const val TAG = "NorrklangDiag"
    private const val MAX_EVENTS = 50
    private const val MAX_DETAIL_CHARS = 300
    private const val MAX_CRASH_CHARS = 12_000

    private val lock = Any()
    private var crashFile: File? = null
    private var eventsFile: File? = null
    private val events = ArrayDeque<String>()

    /** Idempotent; call from every app module's Application.onCreate. */
    fun install(context: Context) {
        synchronized(lock) {
            if (crashFile != null) return
            runCatching {
                val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
                crashFile = File(dir, "last-crash.txt")
                eventsFile = File(dir, "events.txt").also { file ->
                    if (file.exists()) {
                        file.readLines().takeLast(MAX_EVENTS).forEach(events::addLast)
                    }
                }
            }
            installCrashHandler()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashFile?.writeText(
                    "${timestamp()} crash on thread ${thread.name}\n${stackTrace(throwable)}"
                        .take(MAX_CRASH_CHARS),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun record(where: String, throwable: Throwable) {
        record(
            where,
            "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}".trim(':', ' '),
        )
    }

    fun record(where: String, detail: String) {
        // runCatching also covers android.util.Log, which throws in plain
        // JVM unit tests.
        runCatching { Log.w(TAG, "$where: $detail") }
        runCatching {
            synchronized(lock) {
                events.addLast("${timestamp()} $where — ${detail.take(MAX_DETAIL_CHARS)}")
                while (events.size > MAX_EVENTS) events.removeFirst()
                eventsFile?.writeText(events.joinToString("\n"))
            }
        }
    }

    /** The persisted stack of the last crash, or null when there is none. */
    fun lastCrash(): String? = synchronized(lock) {
        runCatching { crashFile?.takeIf(File::exists)?.readText() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /** Recorded events, newest first. */
    fun recentEvents(): List<String> = synchronized(lock) { events.reversed() }

    /** Newest-first dump for the settings screen; empty string when clean. */
    fun snapshot(): String {
        val crash = lastCrash()
        val events = recentEvents()
        return buildString {
            if (crash != null) append(crash)
            if (events.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(events.joinToString("\n"))
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            runCatching { crashFile?.delete() }
            runCatching { eventsFile?.delete() }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun stackTrace(throwable: Throwable): String =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
}
