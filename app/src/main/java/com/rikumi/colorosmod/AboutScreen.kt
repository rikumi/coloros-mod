package com.rikumi.colorosmod

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ABOUT_PAGE = "about"
internal const val LICENSES_PAGE = "open_source"
private const val PROJECT_URL = "https://github.com/rikumi/coloros-mod"
private const val DEVELOPER_URL = "https://github.com/rikumi"

private fun openAboutLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_cannot_open_link, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit, showBack: Boolean = true) {
    val context = LocalContext.current
    val list = rememberSettingsListScroll(rememberLazyListState())
    Scaffold(topBar = {
        AboutTopBar(stringResource(R.string.about_title), list, onBack, showBack)
    }) { padding ->
        SettingsLazyColumn(list, Modifier.fillMaxSize().padding(padding)) {
            item { SettingsSmallTitle(stringResource(R.string.about_developer)) }
            item {
                SettingsCard {
                    ArrowPreference(
                        title = "Rikumi", summary = DEVELOPER_URL,
                        summaryColor = settingsSummaryColors(),
                        startAction = {
                            Image(painterResource(R.drawable.github_avatar), contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape))
                        },
                        onClick = { openAboutLink(context, DEVELOPER_URL) },
                    )
                }
            }
            item {
                SettingsCard {
                    AboutLinkRow(stringResource(R.string.about_source), PROJECT_URL, PROJECT_URL)
                    SettingsItemDivider()
                    AboutLinkRow(stringResource(R.string.about_feedback), stringResource(R.string.about_feedback_summary), "$PROJECT_URL/issues")
                    SettingsItemDivider()
                    AboutLinkRow(stringResource(R.string.about_sister_project), "https://github.com/rikumi/flyme-mod", "https://github.com/rikumi/flyme-mod")
                    SettingsItemDivider()
                    AboutLinkRow(stringResource(R.string.about_floating_lyrics), "https://github.com/rikumi/liri", "https://github.com/rikumi/liri")
                    SettingsItemDivider()
                    ArrowPreference(title = stringResource(R.string.about_open_source), onClick = onOpenLicenses)
                }
            }
            item { Box(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AboutLinkRow(title: String, summary: String, url: String) {
    val context = LocalContext.current
    ArrowPreference(title = title, summary = summary, summaryColor = settingsSummaryColors(),
        onClick = { openAboutLink(context, url) })
}

private data class OpenSourceComponent(val name: String, val url: String, val licenseAsset: String)

private val OPEN_SOURCE_COMPONENTS = listOf(
    OpenSourceComponent("COUI", "https://github.com/suqi8/coui", "licenses/coui-LICENSE"),
    OpenSourceComponent("Inter", "https://github.com/rsms/inter", "clock_fonts/inter-OFL.txt"),
    OpenSourceComponent("Lato", "https://github.com/latofonts/lato-source", "clock_fonts/lato-OFL.txt"),
    OpenSourceComponent("Manrope", "https://github.com/googlefonts/manrope", "clock_fonts/manrope-OFL.txt"),
    OpenSourceComponent("Rubik", "https://github.com/googlefonts/rubik", "clock_fonts/rubik-OFL.txt"),
    OpenSourceComponent("Cascadia Code", "https://github.com/microsoft/cascadia-code", "system_fonts/cascadia-OFL.txt"),
    OpenSourceComponent("Fira Code", "https://github.com/tonsky/FiraCode", "system_fonts/fira-OFL.txt"),
    OpenSourceComponent("JetBrains Mono", "https://github.com/JetBrains/JetBrainsMono", "system_fonts/jetbrains-OFL.txt"),
    OpenSourceComponent("Maple Mono", "https://github.com/subframe7536/maple-font", "system_fonts/maple-OFL.txt"),
)

@Composable
internal fun OpenSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val list = rememberSettingsListScroll(rememberLazyListState())
    var licenses by remember { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(Unit) {
        licenses = withContext(Dispatchers.IO) {
            OPEN_SOURCE_COMPONENTS.associate { component ->
                component.licenseAsset to context.assets.open(component.licenseAsset).bufferedReader().use { it.readText() }
            }
        }
    }
    Scaffold(topBar = {
        AboutTopBar(stringResource(R.string.about_open_source), list, onBack, true)
    }) { padding ->
        SettingsLazyColumn(list, Modifier.fillMaxSize().padding(padding)) {
            item { Box(Modifier.height(12.dp)) }
            if (licenses == null) {
                item { SettingsSmallTitle(stringResource(R.string.ui_loading)) }
            } else {
                OPEN_SOURCE_COMPONENTS.forEach { component ->
                    item(key = component.name) {
                        SettingsCard {
                            AboutLinkRow(component.name, component.url, component.url)
                            SettingsItemDivider()
                            SelectionContainer {
                                Text(text = licenses.orEmpty().getValue(component.licenseAsset),
                                    style = COUITheme.textStyles.body2,
                                    color = COUITheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
            item { Box(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AboutTopBar(title: String, list: SettingsListScroll, onBack: () -> Unit, showBack: Boolean) {
    SettingsTopAppBar(title = title, scrollBehavior = list.appBar, navigationIcon = if (showBack) {
        {
            IconButton(onClick = onBack) {
                Icon(COUIIcons.Back, stringResource(R.string.ui_back), modifier = Modifier.size(SETTINGS_BACK_ICON))
            }
        }
    } else null)
}
