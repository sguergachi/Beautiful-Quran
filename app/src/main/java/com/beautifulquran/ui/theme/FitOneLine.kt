package com.beautifulquran.ui.theme

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A one-line label that is set down in half-sp steps until it fits, rather
 * than cut off: a reciter's or chapter's name must read whole on a narrow
 * phone at a large system text size, and these lists keep one row height
 * (#897), so the name may not wrap either.
 *
 * [size] is the label's own size and stays the ceiling, so nothing changes
 * wherever it already fits. The floor is the size the label has at the
 * system's default text size: a reader who asked for larger text may get
 * less of the increase on a line too narrow for all of it, but never type
 * smaller than everyone else's. At the default size the floor is the ceiling,
 * so this only ever acts on enlarged text. Pair it with `maxLines = 1`,
 * keeping an ellipsis for whatever still overflows at the floor.
 */
@Composable
fun fitOneLine(size: TextUnit): TextAutoSize {
    val max = size.value
    // The label's px at a font scale of 1, read back as sp at the current
    // (possibly non-linear) scale — Dp.toSp runs the system's converter.
    val unscaled = with(LocalDensity.current) { max.dp.toSp() }.value
    val min = minOf(unscaled, max)
    return TextAutoSize.StepBased(
        minFontSize = min.sp,
        maxFontSize = max.sp,
        stepSize = FIT_ONE_LINE_STEP_SP.sp,
    )
}

/** The step a fitted label is set down by. */
const val FIT_ONE_LINE_STEP_SP = 0.5f
