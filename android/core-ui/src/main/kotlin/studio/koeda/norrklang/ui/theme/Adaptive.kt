package studio.koeda.norrklang.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/*
 * Two discrete window-size steps, not continuous scaling. Values stay in
 * sp/dp so font-scale and OEM density still multiply on top. The expanded
 * step exists for wide head units (often 1400+ dp), where the phone-sized
 * form would float tiny in empty space.
 */

/** Window widths at or above this get the expanded dimensions and type scale. */
private val ExpandedMinWidth = 840.dp

/** Dimensions for the centered form screens (sign-in, settings). */
data class FormDimens(
    val maxWidth: Dp,
    val controlMinHeight: Dp,
    val itemSpacing: Dp,
    val screenPadding: Dp,
)

internal val DefaultFormDimens = FormDimens(
    maxWidth = 480.dp,
    controlMinHeight = 56.dp,
    itemSpacing = 16.dp,
    screenPadding = 32.dp,
)

internal val ExpandedFormDimens = FormDimens(
    maxWidth = 640.dp,
    controlMinHeight = 64.dp,
    itemSpacing = 20.dp,
    screenPadding = 48.dp,
)

/** Provided by [NorrklangTheme]; matches the typography step it selected. */
val LocalFormDimens = staticCompositionLocalOf { DefaultFormDimens }

@Composable
internal fun isExpandedWindow(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { widthPx.toDp() } >= ExpandedMinWidth
}

/** Baseline M3 type scale enlarged for big screens viewed at arm's length. */
internal val ExpandedTypography = Typography().scaled(1.25f)

private fun Typography.scaled(factor: Float): Typography = Typography(
    displayLarge = displayLarge.scaled(factor),
    displayMedium = displayMedium.scaled(factor),
    displaySmall = displaySmall.scaled(factor),
    headlineLarge = headlineLarge.scaled(factor),
    headlineMedium = headlineMedium.scaled(factor),
    headlineSmall = headlineSmall.scaled(factor),
    titleLarge = titleLarge.scaled(factor),
    titleMedium = titleMedium.scaled(factor),
    titleSmall = titleSmall.scaled(factor),
    bodyLarge = bodyLarge.scaled(factor),
    bodyMedium = bodyMedium.scaled(factor),
    bodySmall = bodySmall.scaled(factor),
    labelLarge = labelLarge.scaled(factor),
    labelMedium = labelMedium.scaled(factor),
    labelSmall = labelSmall.scaled(factor),
)

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)
