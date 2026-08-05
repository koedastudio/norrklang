package studio.koeda.norrklang.buildlogic

/**
 * Single source of truth for SDK levels and the app-version scheme.
 *
 * The version lives in android/gradle.properties (`norrklang.version`,
 * Flutter style "<versionName>+<buildNumber>") — the only edit needed before
 * a release; this object just parses it.
 *
 * versionCode = semverBase * 1000 + buildNumber * 10 + formFactor
 * (0 = mobile, 1 = automotive). Both APKs share one applicationId, so every
 * upload needs a unique versionCode; the "+N" build number allows re-uploads
 * of the same user-visible version (Play rejects duplicate versionCodes).
 */
object ProjectDefaults {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36

    // AAOS fleet floor: cars in the field still ship Android 9–11; keep mobile
    // aligned to avoid divergent code paths.
    const val MIN_SDK = 28

    // versionCode form-factor suffix per app module; unknown modules fail
    // versionCode() at configuration time.
    private val FORM_FACTORS = mapOf(
        "app-mobile" to 0,
        "app-automotive" to 1,
    )

    private val VERSION_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)\+(\d+)""")

    fun versionName(version: String): String = version.substringBefore('+')

    fun versionCode(version: String, moduleName: String): Int {
        val formFactor = requireNotNull(FORM_FACTORS[moduleName]) {
            "Unknown app module \"$moduleName\" — add it to ProjectDefaults.FORM_FACTORS"
        }
        val match = requireNotNull(VERSION_REGEX.matchEntire(version)) {
            "norrklang.version in android/gradle.properties must look like \"1.2.3+0\" (got \"$version\")"
        }
        val (major, minor, patch, build) = match.destructured.toList().map { it.toInt() }
        require(minor in 0..99 && patch in 0..99 && build in 0..99) {
            "norrklang.version: minor, patch and the +N build number must each be 0–99 (got \"$version\")"
        }
        val semverBase = major * 10_000 + minor * 100 + patch
        return semverBase * 1_000 + build * 10 + formFactor
    }
}
