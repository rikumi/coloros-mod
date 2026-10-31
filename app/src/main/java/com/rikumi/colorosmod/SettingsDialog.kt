package com.rikumi.colorosmod

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

/** Native COUI panel and adaptive button bar, with annotated risk text in the message slot. */
@Composable
internal fun SettingsConfirmDialog(
    title: String,
    text: AnnotatedString,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.ui_confirm),
    dismissLabel: String = stringResource(R.string.ui_cancel),
) {
    var visible by remember { mutableStateOf(true) }
    var confirmed by remember { mutableStateOf(false) }
    var multiline by remember(text) { mutableStateOf(false) }
    val maxTextHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    WindowDialog(
        show = visible,
        title = title,
        onDismissRequest = { visible = false },
        onDismissFinished = { if (confirmed) onConfirm() else onDismiss() },
    ) {
        // WindowDialog.summary accepts only String. Retain annotated spans with slightly roomier message spacing.
        Text(
            text = text,
            color = DialogDefaults.summaryColor(),
            fontSize = COUITheme.textStyles.body2.fontSize,
            lineHeight = 1.5f.em,
            textAlign = if (multiline) TextAlign.Start else TextAlign.Center,
            onTextLayout = { multiline = it.lineCount > 1 },
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = DialogDefaults.insideMargin.width)
                .heightIn(max = maxTextHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (multiline) 16.dp else 18.dp),
        )
        DialogButtonBar(
            negative = DialogButtonBarAction(text = dismissLabel, enabled = visible, onClick = { visible = false }),
            positive = DialogButtonBarAction(text = confirmLabel, enabled = visible, onClick = {
                confirmed = true
                visible = false
            }),
        )
    }
}
