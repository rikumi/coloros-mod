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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun FontProgressDialog(title: String, progress: SystemFontStore.Progress, cancelling: Boolean, onCancel: () -> Unit) {
    val detail = if (progress.total > 0) String.format(java.util.Locale.getDefault(),
        "%.1f / %.1f MB · %d%%", progress.completed / 1048576.0, progress.total / 1048576.0,
        (progress.completed * 100 / progress.total).toInt()) else ""
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = {},
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(if (cancelling) R.string.ui_font_cancelling else progress.stage), color = DialogDefaults.summaryColor())
            if (!cancelling && !progress.detail.isNullOrEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = progress.detail,
                        color = DialogDefaults.summaryColor(),
                        fontSize = COUITheme.textStyles.body2.fontSize,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = if (!cancelling && progress.total > 0)
                    (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) else null,
            )
            if (!cancelling && detail.isNotEmpty()) Text(text = detail, color = DialogDefaults.summaryColor())
        }
        DialogButtonBar(
            negative = null,
            colors = ButtonDefaults.textButtonColorsBorderless(
                disabledTextColor = colorResource(R.color.settings_summary_text_disabled),
            ),
            positive = DialogButtonBarAction(text = stringResource(R.string.ui_cancel), enabled = !cancelling && progress.canCancel, onClick = onCancel),
        )
    }
}

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
