plugins {
    id("norrklang.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "studio.koeda.norrklang.data"

    // OkHttp's static platform probe calls android.util.Log.isLoggable on the
    // unit-test classpath; return defaults instead of throwing "not mocked".
    testOptions.unitTests.isReturnDefaultValues = true

    // For the on-device JDK-API smoke test (src/androidTest) — run it against
    // the OLDEST practical AVD to catch JDK-only calls in the pure-JVM
    // modules; see JdkApiOnDeviceTest.
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core-subsonic"))
    api(project(":core-plex"))
    api(project(":core-jellyfin"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // mTLS public-key upload / certificate download (MtlsManager) and the
    // shared mTLS OkHttp client (MtlsOkHttpProvider).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // PKCS#10 certificate-request generation for the mTLS upload
    // (MtlsManager.csrPem); BouncyCastle is the only signer that builds CSRs.
    implementation(libs.bouncycastle.pkix)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.bouncycastle.prov)
    testImplementation(libs.bouncycastle.pkix)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
