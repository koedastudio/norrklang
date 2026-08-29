package studio.koeda.norrklang.data.settings

/**
 * Playback quality tier, configured separately per network type (see
 * [ServerSettingsRepository.streamQualityWifi] / [streamQualityCellular]).
 * [maxKbps] is the bitrate cap handed to the provider's transcoder; null
 * streams the original file (bit-perfect and gapless, but a lossless library
 * can exceed what a cellular path delivers — the reason the tiers exist).
 */
enum class StreamQuality(val storageValue: String, val maxKbps: Int?) {
    ORIGINAL("original", null),
    HIGH("high", 320),
    MEDIUM("medium", 192),
    LOW("low", 128),
    ;

    companion object {
        /** Fresh-install behavior on Wi-Fi: unconstrained, keep bit-perfect. */
        val DEFAULT_WIFI = ORIGINAL

        /**
         * Fresh-install behavior on cellular: capped. A lossless stream that
         * exceeds the cellular path (Plex remote bitrate limits, Relay) fails
         * as "the app doesn't play" — far worse than 320 kbps in a car cabin.
         * Bit-perfect over cellular is the opt-in, not the other way around.
         */
        val DEFAULT_CELLULAR = HIGH

        /** Null for unknown stored values (downgrades) — caller falls back. */
        fun fromStorageValue(value: String): StreamQuality? =
            entries.firstOrNull { it.storageValue == value }
    }
}
