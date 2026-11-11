package studio.koeda.norrklang.data.diagnostics

import java.lang.reflect.Proxy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Check
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Outcome
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Reason
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Report
import studio.koeda.norrklang.data.diagnostics.ConnectionTest.Station
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository

class ConnectionTestTest {

    /** Every member the test does not script fails as unused. */
    private val unused = Proxy.newProxyInstance(
        MusicRepository::class.java.classLoader,
        arrayOf(MusicRepository::class.java),
    ) { _, method, _ -> error("unused ${method.name}") } as MusicRepository

    private open inner class Server(
        private val stations: List<RadioStation> = emptyList(),
    ) : MusicRepository by unused {
        var invalidated = false
        override suspend fun ping() = Unit
        override fun invalidateCache() {
            invalidated = true
        }
        override suspend fun libraries(): List<MusicLibrary> = emptyList()
        override suspend fun artists(): List<Artist> = emptyList()
        override suspend fun playlists(): List<Playlist> = emptyList()
        override suspend fun radioStations(): List<RadioStation> = stations
    }

    /** Each clock read advances 5 ms, so every timed step takes 5 ms. */
    private fun test(
        repository: MusicRepository,
        streamStatus: suspend (String) -> Int = { 200 },
    ): ConnectionTest {
        var now = 0L
        return ConnectionTest(repository, streamStatus) { now.also { now += 5_000_000 } }
    }

    private fun station(name: String) = RadioStation(id = name, name = name, streamUrl = "http://$name/stream")

    @Test
    fun `a reachable server is timed and its lists counted from a cleared cache`() = runTest {
        val server = Server(listOf(station("a"), station("b")))

        val report = test(server).run().last()

        assertTrue(server.invalidated)
        assertTrue(report.finished)
        assertEquals(Outcome.Ok(5), report.checks[Check.SERVER])
        assertEquals(Outcome.Ok(5, count = 0), report.checks[Check.ARTISTS])
        assertEquals(Outcome.Ok(5, count = 2), report.checks[Check.STATIONS])
        assertEquals(listOf(Station("a", Outcome.Ok(5)), Station("b", Outcome.Ok(5))), report.stations)
    }

    @Test
    fun `an unreachable server skips everything else`() = runTest {
        val server = object : Server() {
            override suspend fun ping() = throw MusicException.NetworkError(UnknownHostException("music.example"))
        }

        val reports = test(server).run().toList()

        assertEquals(Report(), reports.first())
        assertEquals(
            Report(mapOf(Check.SERVER to Outcome.Failed(Reason.HOST_NOT_FOUND)), finished = true),
            reports.last(),
        )
    }

    @Test
    fun `one failing list does not stop the rest`() = runTest {
        val server = object : Server() {
            override suspend fun artists() = throw MusicException.NetworkError(SocketTimeoutException())
        }

        val report = test(server).run().last()

        assertEquals(Outcome.Failed(Reason.TIMEOUT), report.checks[Check.ARTISTS])
        assertEquals(Outcome.Ok(5, count = 0), report.checks[Check.PLAYLISTS])
    }

    @Test
    fun `station failures are told apart`() = runTest {
        val server = Server(listOf(station("ok"), station("refused"), station("gone")))

        val report = test(server) { url ->
            when {
                "refused" in url -> throw ConnectException("Connection refused")
                "gone" in url -> 404
                else -> 200
            }
        }.run().last()

        assertEquals(
            listOf(
                Station("ok", Outcome.Ok(5)),
                Station("refused", Outcome.Failed(Reason.REFUSED)),
                Station("gone", Outcome.Failed(Reason.HTTP_STATUS, httpStatus = 404)),
            ),
            report.stations,
        )
    }

    @Test
    fun `a rejected sign-in and an unknown error are classified`() {
        assertEquals(Outcome.Failed(Reason.SIGN_IN), ConnectionTest.failureOf(MusicException.AuthFailed("no")))
        assertEquals(
            Outcome.Failed(Reason.OTHER, detail = "ServerError: boom"),
            ConnectionTest.failureOf(MusicException.ServerError("boom")),
        )
    }

    @Test
    fun `the log leads with the checks and names only failing stations`() {
        val report = Report(
            checks = mapOf(
                Check.SERVER to Outcome.Ok(142),
                Check.LIBRARIES to Outcome.Ok(80, count = 2),
                Check.ARTISTS to Outcome.Failed(Reason.TIMEOUT),
            ),
            stations = listOf(
                Station("KEXP", Outcome.Ok(210)),
                Station("Dead FM", Outcome.Failed(Reason.REFUSED)),
                Station("Old FM", Outcome.Failed(Reason.HTTP_STATUS, httpStatus = 404)),
            ),
            finished = true,
        )

        assertEquals(
            listOf(
                "server 142 ms; libraries 2 in 80 ms; artists failed: timeout",
                "stations reachable 1 of 3, slowest 210 ms; Dead FM: refused; Old FM: HTTP 404",
            ),
            ConnectionTest.logLines(report),
        )
    }
}
