package studio.koeda.norrklang.data.diagnostics

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.runSuspendCatching

/**
 * The Settings connection test: one server round trip, the list calls
 * browsing starts with, then each radio station's stream address. The result
 * is also written to [Diagnostics], so a problem report carries it.
 */
class ConnectionTest internal constructor(
    private val repository: MusicRepository,
    private val streamStatus: suspend (String) -> Int,
    private val nanoTime: () -> Long,
) {

    @Inject
    constructor(repository: MusicRepository) : this(repository, StreamProbe::status, System::nanoTime)

    /** The server-side checks, in run order. */
    enum class Check { SERVER, LIBRARIES, ARTISTS, PLAYLISTS, STATIONS }

    sealed interface Outcome {
        /** [count] is the list size, for the checks that fetch one. */
        data class Ok(val millis: Long, val count: Int? = null) : Outcome

        /** [detail] is for the log only: it can embed a URL, which [Diagnostics] strips. */
        data class Failed(
            val reason: Reason,
            val httpStatus: Int? = null,
            val detail: String? = null,
        ) : Outcome
    }

    enum class Reason { SIGN_IN, HOST_NOT_FOUND, REFUSED, TIMEOUT, TLS, HTTP_STATUS, OTHER }

    /** A null [outcome] is still being checked. */
    data class Station(val name: String, val outcome: Outcome?)

    /** A check missing from [checks] is running, or was skipped once [finished]. */
    data class Report(
        val checks: Map<Check, Outcome> = emptyMap(),
        val stations: List<Station> = emptyList(),
        val finished: Boolean = false,
    )

    /** Emits the report as it fills in; the last emission is [Report.finished]. */
    fun run(): Flow<Report> = channelFlow {
        var report = Report()
        val lock = Mutex()
        suspend fun update(change: (Report) -> Report) = lock.withLock {
            report = change(report)
            send(report)
        }
        suspend fun check(check: Check, count: suspend () -> Int?) =
            timed(count).also { outcome -> update { it.copy(checks = it.checks + (check to outcome)) } }

        update { it }
        if (check(Check.SERVER) { repository.ping(); null } is Outcome.Ok) {
            // Cached lists would answer in no time and hide a slow server.
            repository.invalidateCache()
            check(Check.LIBRARIES) { repository.libraries().size }
            check(Check.ARTISTS) { repository.artists().size }
            check(Check.PLAYLISTS) { repository.playlists().size }
            var stations = emptyList<RadioStation>()
            check(Check.STATIONS) { repository.radioStations().also { stations = it }.size }
            stations = stations.take(MAX_STATIONS)
            update { it.copy(stations = stations.map { station -> Station(station.name, null) }) }
            val permits = Semaphore(PARALLEL_PROBES)
            coroutineScope {
                stations.forEachIndexed { index, station ->
                    launch {
                        val outcome = permits.withPermit { probe(station.streamUrl) }
                        update {
                            it.copy(stations = it.stations.toMutableList().also { list ->
                                list[index] = list[index].copy(outcome = outcome)
                            })
                        }
                    }
                }
            }
        }
        update { it.copy(finished = true) }
        // Checks last, so they lead the newest-first log.
        logLines(report).reversed().forEach { Diagnostics.record("connection test", it) }
    }

    private suspend fun probe(url: String): Outcome {
        var status = 0
        val outcome = timed { status = streamStatus(url); null }
        return if (outcome is Outcome.Ok && status !in 200..299) {
            Outcome.Failed(Reason.HTTP_STATUS, httpStatus = status)
        } else {
            outcome
        }
    }

    private suspend fun timed(count: suspend () -> Int?): Outcome {
        val start = nanoTime()
        return runSuspendCatching { count() }.fold(
            onSuccess = { Outcome.Ok((nanoTime() - start) / 1_000_000, it) },
            onFailure = ::failureOf,
        )
    }

    internal companion object {
        /** Keeps the page and the run time bounded on servers with long station lists. */
        const val MAX_STATIONS = 30
        const val PARALLEL_PROBES = 4

        fun failureOf(e: Throwable): Outcome.Failed {
            if (e is MusicException.AuthFailed) return Outcome.Failed(Reason.SIGN_IN)
            val causes = generateSequence(e) { it.cause }.take(8).toList()
            val reason = when {
                // By name: Ktor's timeout types share no supertype, and one extends ConnectException.
                causes.any { "Timeout" in it.javaClass.simpleName } -> Reason.TIMEOUT
                causes.any { it is UnknownHostException } -> Reason.HOST_NOT_FOUND
                causes.any { it is ConnectException } -> Reason.REFUSED
                causes.any { it is SSLException } -> Reason.TLS
                else -> return Outcome.Failed(
                    Reason.OTHER,
                    detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim(':', ' '),
                )
            }
            return Outcome.Failed(reason)
        }

        /** The [Diagnostics] entries for a finished run: the checks, then the stations if any. */
        fun logLines(report: Report): List<String> = buildList {
            add(
                Check.entries.mapNotNull { check ->
                    val name = check.name.lowercase()
                    when (val outcome = report.checks[check]) {
                        null -> null
                        is Outcome.Ok -> when (val count = outcome.count) {
                            null -> "$name ${outcome.millis} ms"
                            else -> "$name $count in ${outcome.millis} ms"
                        }
                        is Outcome.Failed -> "$name failed: ${describe(outcome)}"
                    }
                }.joinToString("; "),
            )
            if (report.stations.isNotEmpty()) {
                val reached = report.stations.mapNotNull { it.outcome as? Outcome.Ok }
                val failed = report.stations.mapNotNull { station ->
                    (station.outcome as? Outcome.Failed)?.let { "${station.name}: ${describe(it)}" }
                }
                val summary = "stations reachable ${reached.size} of ${report.stations.size}" +
                    reached.maxOfOrNull { it.millis }?.let { ", slowest $it ms" }.orEmpty()
                add((listOf(summary) + failed).joinToString("; "))
            }
        }

        private fun describe(failure: Outcome.Failed): String = when (failure.reason) {
            Reason.HTTP_STATUS -> "HTTP ${failure.httpStatus}"
            Reason.OTHER -> failure.detail ?: "other"
            else -> failure.reason.name.lowercase().replace('_', ' ')
        }
    }
}

/** Opens a stream address the way the player does (HttpURLConnection) and reads only the status. */
internal object StreamProbe {

    private const val TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 5

    suspend fun status(url: String): Int = runInterruptible(Dispatchers.IO) {
        var next = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = next.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                // Followed by hand: the player allows http↔https redirects, HttpURLConnection alone does not.
                connection.instanceFollowRedirects = false
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location == null) return@runInterruptible status
                next = URL(next, location)
            } finally {
                connection.disconnect()
            }
        }
        throw ProtocolException("Too many redirects")
    }
}
