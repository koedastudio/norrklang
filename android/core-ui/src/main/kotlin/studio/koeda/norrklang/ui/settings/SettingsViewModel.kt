package studio.koeda.norrklang.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    private val repository: MusicRepository,
) : ViewModel() {

    val sessionState: StateFlow<SessionManager.SessionState> = sessionManager.state

    /** See [ServerSettingsRepository.streamOriginal] — raw/gapless vs transcoded. */
    val streamOriginal: StateFlow<Boolean> = settings.streamOriginal
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ServerSettingsRepository.DEFAULT_STREAM_ORIGINAL,
        )

    fun setStreamOriginal(enabled: Boolean) {
        viewModelScope.launch { settings.setStreamOriginal(enabled) }
    }

    // --- Scrobbling ---

    /** An artist or playlist as shown in the exclusion picker screens. */
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
}
