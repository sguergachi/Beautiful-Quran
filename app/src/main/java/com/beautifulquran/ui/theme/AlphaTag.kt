package com.beautifulquran.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.beautifulquran.R

/**
 * Product-stage mark. Same letterspaced whisper as a settings section
 * label — never a chip, fill, or stroke. Gold is Quranic ornament; this
 * is quiet ink.
 */
@Composable
fun AlphaTag(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.alpha_tag_description)
    Text(
        text = stringResource(R.string.alpha_tag).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = modifier.semantics { contentDescription = description },
    )
}
