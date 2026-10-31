package com.rikumi.colorosmod

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

/** Application confirmation actions hosted by COUI's adaptive window dialog. */
@Composable
internal fun SettingsConfirmDialog(
    title: String,
    text: AnnotatedString,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.ui_confirm),
    dismissLabel: String = stringResource(R.string.ui_cancel),
    confirmColor: Color = Color.Unspecified,
) {
    val maxTextHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss, maxWidth = CONTENT_MAX_WIDTH_DP) {
        Column {
            Text(text = text, color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxTextHeight)
                    .verticalScroll(rememberScrollState()).padding(bottom = 16.dp))
            TextButton(text = confirmLabel, onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    textColor = if (confirmColor == Color.Unspecified) COUITheme.colorScheme.primary else confirmColor))
            TextButton(text = dismissLabel, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
