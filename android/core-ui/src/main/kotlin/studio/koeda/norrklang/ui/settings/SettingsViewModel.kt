package studio.koeda.norrklang.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.diagnostics.ReportMetadata
import studio.koeda.norrklang.data.diagnostics.ReportPayload
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings
import studio.koeda.norrklang.data.settings.StreamQuality

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val repository: MusicRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val sessionState: StateFlow<SessionManager.SessionState> = sessionManager.state

    /** See [ServerSettingsRepository.streamQualityWifi] — per-network tiers. */
    val qualityWifi: StateFlow<StreamQuality> = settings.streamQualityWifi
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StreamQuality.DEFAULT_WIFI,
        )

    val qualityCellular: StateFlow<StreamQuality> = settings.streamQualityCellular
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            StreamQuality.DEFAULT_CELLULAR,
        )

    fun setQualityWifi(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamQualityWifi(quality) }
    }

    fun setQualityCellular(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamQualityCellular(quality) }
    }

    /** See [ServerSettingsRepository.autoplaySimilar] — queue-end radio. */
    val autoplaySimilar: StateFlow<Boolean> = settings.autoplaySimilar
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ServerSettingsRepository.DEFAULT_AUTOPLAY_SIMILAR,
        )

    fun setAutoplaySimilar(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoplaySimilar(enabled) }
    }

    // --- Libraries ---

    /** See [ServerSettingsRepository.excludedLibraryIds] — hidden server libraries. */
    val excludedLibraryIds: StateFlow<Set<String>> = settings.excludedLibraryIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _libraryPicker = MutableStateFlow<PickerState>(PickerState.Loading)

    /** The server's libraries; shared by the main-page summary and both library pages. */
    val libraryPicker: StateFlow<PickerState> = _libraryPicker.asStateFlow()

    /** (Re)fetches the library list; a loaded list is kept unless [force]. */
    fun loadLibraryPicker(force: Boolean = false) {
        if (!force && _libraryPicker.value is PickerState.Loaded) return
        load(_libraryPicker) { repository.libraries().map { PickerItem(it.id, it.name) } }
    }

    /** What the Libraries row reads: "All", "n of m", or nothing known yet. */
    sealed interface LibrariesSummary {
        data object Unknown : LibrariesSummary
        data object All : LibrariesSummary
        data class Some(val selected: Int, val total: Int) : LibrariesSummary
    }

    val librariesSummary: StateFlow<LibrariesSummary> =
        combine(libraryPicker, excludedLibraryIds, ::summarizeLibraries)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrariesSummary.Unknown)

    /** [selected] false excludes the library from browsing (see LibraryScope). */
    fun setLibrarySelected(libraryId: String, selected: Boolean) {
        viewModelScope.launch { settings.setLibraryExcluded(libraryId, !selected) }
    }

    fun setLibraryScrobbleExcluded(libraryId: String, excluded: Boolean) {
        viewModelScope.launch { settings.setLibraryScrobbleExcluded(libraryId, excluded) }
    }

    // --- Scrobbling ---

    /** An artist, playlist or library as shown in the picker screens. */
    data class PickerItem(val id: String, val name: String)

    /** Server-fetched contents of one exclusion picker screen. */
    sealed interface PickerState {
        data object Loading : PickerState
        data class Loaded(val items: List<PickerItem>) : PickerState
        data object Error : PickerState
    }

    val scrobbleSettings: StateFlow<ScrobbleSettings> = settings.scrobbleSettings
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScrobbleSettings.DEFAULT,
        )

    private val _artistPicker = MutableStateFlow<PickerState>(PickerState.Loading)
    val artistPicker: StateFlow<PickerState> = _artistPicker.asStateFlow()

    private val _playlistPicker = MutableStateFlow<PickerState>(PickerState.Loading)
    val playlistPicker: StateFlow<PickerState> = _playlistPicker.asStateFlow()

    /** (Re)fetches the artist list; call on entering the picker or on retry. */
    fun loadArtistPicker() = load(_artistPicker) {
        repository.artists().map { PickerItem(it.id, it.name) }
    }

    /** (Re)fetches the playlist list; call on entering the picker or on retry. */
    fun loadPlaylistPicker() = load(_playlistPicker) {
        repository.playlists().map { PickerItem(it.id, it.name) }
    }

    private fun load(
        state: MutableStateFlow<PickerState>,
        fetch: suspend () -> List<PickerItem>,
    ) {
        state.value = PickerState.Loading
        viewModelScope.launch {
            state.value = runCatching { PickerState.Loaded(fetch()) }
                .getOrDefault(PickerState.Error)
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setScrobblingEnabled(enabled) }
    }

    fun setArtistExcluded(artistId: String, excluded: Boolean) {
        viewModelScope.launch { settings.setArtistScrobbleExcluded(artistId, excluded) }
    }

    fun setPlaylistExcluded(playlistId: String, excluded: Boolean) {
        viewModelScope.launch { settings.setPlaylistScrobbleExcluded(playlistId, excluded) }
    }

    /**
     * Fire-and-forget: react to [sessionState] flipping to SignedOut — a
     * captured completion callback could outlive its screen.
     */
    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }

    // --- Diagnostics (see Diagnostics: cars offer users no logcat) ---

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    /** The report QR's URL (see [ReportPayload]); null when the log is empty. */
    private val _reportUrl = MutableStateFlow<String?>(null)
    val reportUrl: StateFlow<String?> = _reportUrl.asStateFlow()

    /** (Re)reads the log; call on entering the diagnostics page. */
    fun loadDiagnostics() {
        _diagnostics.value = Diagnostics.snapshot()
        _reportUrl.value = ReportPayload.buildUrl(
            ReportMetadata.from(context),
            Diagnostics.lastCrash(),
            Diagnostics.recentEvents(),
        )
    }

    fun clearDiagnostics() {
        Diagnostics.clear()
        _diagnostics.value = ""
        _reportUrl.value = null
    }
}

/** Pure: stale excluded ids don't count; one library, or nothing left, reads as "All". */
internal fun summarizeLibraries(
    picker: SettingsViewModel.PickerState,
    excludedIds: Set<String>,
): SettingsViewModel.LibrariesSummary {
    val items = (picker as? SettingsViewModel.PickerState.Loaded)?.items
        ?: return SettingsViewModel.LibrariesSummary.Unknown
    val total = items.size
    val selected = items.count { it.id !in excludedIds }
    return if (total <= 1 || selected == total || selected == 0) {
        SettingsViewModel.LibrariesSummary.All
    } else {
        SettingsViewModel.LibrariesSummary.Some(selected, total)
    }
}
