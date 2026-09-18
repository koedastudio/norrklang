package studio.koeda.norrklang.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import studio.koeda.norrklang.ui.settings.SettingsViewModel.LibrariesSummary
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerItem
import studio.koeda.norrklang.ui.settings.SettingsViewModel.PickerState

class SettingsViewModelTest {

    private val three = PickerState.Loaded(
        listOf(PickerItem("1", "Music"), PickerItem("2", "Kids"), PickerItem("3", "Multichannel")),
    )

    @Test
    fun `unknown while the list is loading or failed`() {
        assertEquals(LibrariesSummary.Unknown, summarizeLibraries(PickerState.Loading, setOf("2")))
        assertEquals(LibrariesSummary.Unknown, summarizeLibraries(PickerState.Error, emptySet()))
    }

    @Test
    fun `nothing excluded reads as all`() {
        assertEquals(LibrariesSummary.All, summarizeLibraries(three, emptySet()))
    }

    @Test
    fun `a subset counts selected of total`() {
        assertEquals(LibrariesSummary.Some(2, 3), summarizeLibraries(three, setOf("2")))
    }

    @Test
    fun `stale excluded ids are ignored`() {
        assertEquals(LibrariesSummary.All, summarizeLibraries(three, setOf("gone")))
    }

    @Test
    fun `a single library or everything excluded reads as all`() {
        val one = PickerState.Loaded(listOf(PickerItem("1", "Music")))
        assertEquals(LibrariesSummary.All, summarizeLibraries(one, setOf("1")))
        assertEquals(LibrariesSummary.All, summarizeLibraries(three, setOf("1", "2", "3")))
    }
}
