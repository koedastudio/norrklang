package studio.koeda.norrklang.media

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

// The custom CommandButtons the car hosts render, plus the session-command
// actions behind them. Handled in LibrarySessionCallback.onCustomCommand;
// registered on the session in NorrklangMediaLibraryService.

internal const val ACTION_TOGGLE_SHUFFLE =
    "studio.koeda.norrklang.action.TOGGLE_SHUFFLE"

internal const val ACTION_TOGGLE_FAVORITE =
    "studio.koeda.norrklang.action.TOGGLE_FAVORITE"

internal const val ACTION_FAVORITE_ALBUM_ADD =
    "studio.koeda.norrklang.action.FAVORITE_ALBUM_ADD"
internal const val ACTION_FAVORITE_ALBUM_REMOVE =
    "studio.koeda.norrklang.action.FAVORITE_ALBUM_REMOVE"

/**
 * Browse-view buttons on album items ("custom browse actions").
 * Registered once at session build; each album opts into exactly one
 * via its supported commands (outline heart on non-favorites, filled
 * on favorites). Legacy hosts require the icon as a URI — the
 * built-in icon constants don't survive the conversion.
 */
internal fun albumFavoriteButtons(context: Context): List<CommandButton> = listOf(
    CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
        .setDisplayName(context.getString(R.string.command_add_favorite))
        .setIconUri(context.resourceUri(R.drawable.ic_action_favorite_outline))
        .setSessionCommand(SessionCommand(ACTION_FAVORITE_ALBUM_ADD, Bundle.EMPTY))
        .build(),
    CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
        .setDisplayName(context.getString(R.string.command_remove_favorite))
        .setIconUri(context.resourceUri(R.drawable.ic_action_favorite_filled))
        .setSessionCommand(SessionCommand(ACTION_FAVORITE_ALBUM_REMOVE, Bundle.EMPTY))
        .build(),
)

/**
 * The playback-row custom buttons in display order: shuffle first,
 * then the favorite heart. Always set the full list —
 * setMediaButtonPreferences replaces it wholesale.
 */
internal fun playbackButtons(
    context: Context,
    shuffleOn: Boolean,
    favorite: Boolean,
): List<CommandButton> = listOf(
    shuffleButton(context, shuffleOn),
    favoriteButton(context, favorite),
)

/** Playback-row shuffle toggle; [shuffleOn] mirrors the player's mode. */
@OptIn(UnstableApi::class) // setSlots
private fun shuffleButton(context: Context, shuffleOn: Boolean): CommandButton =
    CommandButton.Builder(
        if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
    )
        .setDisplayName(
            context.getString(
                if (shuffleOn) {
                    R.string.command_shuffle_off
                } else {
                    R.string.command_shuffle_on
                },
            ),
        )
        .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
        // Overflow only: keeps custom buttons out of the reserved
        // skip-prev/next slots, so they don't jump into the next-button
        // position on single-track queues.
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

/**
 * Playback-row heart toggling the current track's favorite ("starred")
 * state on the server; [favorite] renders the filled heart.
 */
@OptIn(UnstableApi::class) // setSlots
private fun favoriteButton(context: Context, favorite: Boolean): CommandButton =
    CommandButton.Builder(
        if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
    )
        .setDisplayName(
            context.getString(
                if (favorite) {
                    R.string.command_remove_favorite
                } else {
                    R.string.command_add_favorite
                },
            ),
        )
        .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
        // Overflow only — see shuffleButton.
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()
