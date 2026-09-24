package studio.koeda.norrklang.data.diagnostics

import android.content.Context
import android.os.Build

/**
 * App/device facts a problem report leads with. Kept to what triage needs —
 * nothing here identifies the user or their server.
 */
data class ReportMetadata(
    val appVersion: String,
    val apiLevel: Int,
    val device: String,
) {
    companion object {
        fun from(context: Context): ReportMetadata = ReportMetadata(
            appVersion = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?",
            apiLevel = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
    }
}

/**
 * Builds the URL behind the "report a problem" QR code on the diagnostics
 * screen: `https://norrklang.app/report#1.<base64url(rawDeflate(text))>`.
 *
 * Encoded by [FragmentPayload]; the /report page (www/src/pages/report.astro)
 * decodes it client-side and offers a prefilled GitHub issue. Both sides
 * must agree on [FORMAT_VERSION].
 *
 * QR codes scanned off a car screen stay reliable only so big, so the text is
 * trimmed (crash head + newest events) until the URL fits [MAX_URL_LENGTH] —
 * the full log stays readable on the diagnostics screen itself.
 */
object ReportPayload {

    const val REPORT_URL = "https://norrklang.app/report"
    const val FORMAT_VERSION = "1"

    const val MAX_URL_LENGTH = FragmentPayload.MAX_URL_LENGTH

    /** Trim starting points; halved together until the URL fits. */
    private const val CRASH_LINES = 12
    private const val EVENT_LINES = 15

    /**
     * The report URL, or null when there is nothing to report. [events] are
     * expected newest first (see [Diagnostics.recentEvents]).
     */
    fun buildUrl(metadata: ReportMetadata, crash: String?, events: List<String>): String? {
        if (crash.isNullOrBlank() && events.isEmpty()) return null
        var crashBudget = CRASH_LINES
        var eventBudget = EVENT_LINES
        while (true) {
            val url = encode(compose(metadata, crash, events, crashBudget, eventBudget))
            if (url.length <= MAX_URL_LENGTH) return url
            // Deflate flattens long stacks well, so even one more halving
            // usually lands under the cap; the floor is metadata plus one line.
            if (crashBudget <= 1 && eventBudget <= 1) return url
            crashBudget = (crashBudget / 2).coerceAtLeast(1)
            eventBudget = (eventBudget / 2).coerceAtLeast(1)
        }
    }

    private fun compose(
        metadata: ReportMetadata,
        crash: String?,
        events: List<String>,
        crashBudget: Int,
        eventBudget: Int,
    ): String = buildString {
        append("Norrklang ${metadata.appVersion} · API ${metadata.apiLevel} · ${metadata.device}")
        if (!crash.isNullOrBlank()) {
            append("\n== crash ==\n")
            append(crash.lineSequence().take(crashBudget).joinToString("\n"))
        }
        if (events.isNotEmpty()) {
            append("\n== errors ==\n")
            append(events.take(eventBudget).joinToString("\n"))
        }
    }

    private fun encode(text: String): String =
        FragmentPayload.url(REPORT_URL, FORMAT_VERSION, text)
}
