package com.rikumi.colorosmod

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults

/** Match the Settings APK's secondary-text selector instead of COUI's 40% summary default. */
@Composable
internal fun settingsSummaryTextColor() = colorResource(R.color.settings_summary_text)

@Composable
internal fun settingsSummaryColors() = BasicComponentDefaults.summaryColor(
    color = settingsSummaryTextColor(),
    disabledColor = colorResource(R.color.settings_summary_text_disabled),
)
