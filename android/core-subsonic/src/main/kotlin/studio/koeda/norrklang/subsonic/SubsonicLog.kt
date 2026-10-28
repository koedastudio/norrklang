package studio.koeda.norrklang.subsonic

/**
 * Transport-level log sink for [SubsonicClient]. No-op by default (the module
 * is plain JVM and unit-testable without Android); the host app overrides
 * [writer] to route lines to logcat — same hook pattern as
 * [SubsonicEngineProvider].
 *
 * SECURITY: lines carry baseUrl + endpoint + status only. Never log
 * `authParams()` — it embeds the `u`/`t`/`s` auth token.
 */
object SubsonicLog {

    @Volatile
    var writer: ((String) -> Unit)? = null

    fun log(message: String) {
        writer?.invoke(message)
    }
}
