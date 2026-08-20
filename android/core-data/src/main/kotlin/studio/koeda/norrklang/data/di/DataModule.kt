package studio.koeda.norrklang.data.di

import android.content.Context
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
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.repo.DefaultMusicRepository
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.KeystoreCredentialCipher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

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
}

@Module
@InstallIn(SingletonComponent::class)
interface DataBindsModule {

    @Binds
    fun bindMusicRepository(impl: DefaultMusicRepository): MusicRepository

    @Binds
    fun bindCredentialCipher(impl: KeystoreCredentialCipher): CredentialCipher
}
