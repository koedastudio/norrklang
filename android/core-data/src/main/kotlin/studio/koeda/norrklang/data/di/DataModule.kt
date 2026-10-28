package studio.koeda.norrklang.data.di

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.repo.RoutingMusicRepository
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.KeystoreCredentialCipher
import studio.koeda.norrklang.subsonic.SubsonicLog

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** The app's package name — injectable so repositories stay JVM-testable. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPackageName

/** The device name (CSR subject) — injectable so mTLS stays JVM-testable. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceName

private val Context.norrklangDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "norrklang_settings",
    // A truncated prefs file (car cutting power mid-write — ResumptionPersister
    // writes throughout playback) would otherwise fail EVERY read with
    // CorruptionException forever: an unrecoverable error loop the media host
    // gives up on. Starting over signed-out beats a bricked install.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {

    init {
        // Route the JVM Subsonic client's transport logs to logcat so an
        // emulator session shows the real TLS/auth failure, not just the
        // sanitized UI error. Hilt loads this module at app start.
        SubsonicLog.writer = { Log.d("NorrklangSubsonic", it) }
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.norrklangDataStore

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // Without a handler, one background failure (Keystore restore,
                // DataStore I/O) reaches the default handler and kills the
                // process — which a car media host answers by rebinding into
                // the same failure, over and over.
                CoroutineExceptionHandler { _, e -> Diagnostics.record("app-scope", e) },
        )

    @Provides
    @AppPackageName
    fun providePackageName(@ApplicationContext context: Context): String =
        context.packageName

    @Provides
    @DeviceName
    fun provideDeviceName(): String = Build.MODEL ?: "Norrklang"

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient =
        HttpClient(OkHttp.create()) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
            }
        }
}

@Module
@InstallIn(SingletonComponent::class)
interface DataBindsModule {

    @Binds
    fun bindMusicRepository(impl: RoutingMusicRepository): MusicRepository

    @Binds
    fun bindCredentialCipher(impl: KeystoreCredentialCipher): CredentialCipher
}
