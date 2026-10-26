package com.rikumi.colorosmod

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared bottom confirmation dialog with styled content and rounded text actions. */
@Composable
internal fun CouixConfirmDialog(
    title: String,
    text: AnnotatedString,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.ui_confirm),
    dismissLabel: String = stringResource(R.string.ui_cancel),
    confirmColor: Color = Color.Unspecified,
) {
    val actionColor = if (confirmColor == Color.Unspecified) MiuixTheme.colorScheme.primary else confirmColor
    val maxTextHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(R.style.CouixBottomDialogAnimation)
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Card(
                cornerRadius = 20.dp,
                colors = CardDefaults.defaultColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        BasicText(
                            text = title,
                            style = MiuixTheme.textStyles.title4.copy(
                                fontSize = MiuixTheme.textStyles.title4.fontSize * 1.05f,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BasicText(
                            text = text,
                            style = MiuixTheme.textStyles.body1.copy(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                .heightIn(max = maxTextHeight).verticalScroll(rememberScrollState()),
                        )
                    }
                    BasicText(
                        text = confirmLabel,
                        style = MiuixTheme.textStyles.button.copy(
                            color = actionColor,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(percent = 50)).clickable(onClick = onConfirm)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .height(with(LocalDensity.current) { 1.toDp() })
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)))
                    BasicText(
                        text = dismissLabel,
                        style = MiuixTheme.textStyles.button.copy(
                            color = MiuixTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(percent = 50)).clickable(onClick = onDismiss)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
