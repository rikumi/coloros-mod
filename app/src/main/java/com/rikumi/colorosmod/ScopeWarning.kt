package com.rikumi.colorosmod

import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMissingScopes(resumeRequest: Int): Boolean {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val refresh: () -> Unit = {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val services = MainActivity.xposedServices.toList()
                if (services.isEmpty()) return@withContext false
                runCatching {
                    val recommended = ScopeRequirements::class.java.classLoader!!
                        .getResourceAsStream("META-INF/xposed/scope.list")?.bufferedReader()?.use { reader ->
                            reader.lineSequence().map { it.trim() }
                                .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
                        } ?: return@runCatching false
                    val installed = recommended.filterTo(mutableSetOf()) { name ->
                        if (name == "system") false else try {
                            context.packageManager.getApplicationInfo(name, 0).enabled
                        } catch (_: PackageManager.NameNotFoundException) { false }
                    }
                    val selected = services.flatMap { it.scope }.toSet()
                    ScopeRequirements.hasMissing(recommended, installed, selected)
                }.getOrDefault(false)
            }
            missing = result
        }
    }
    DisposableEffect(Unit) {
        val observer = Runnable { scope.launch { refresh() } }
        MainActivity.scopeObservers.add(observer)
        onDispose {
            MainActivity.scopeObservers.remove(observer)
            refreshJob?.cancel()
        }
    }
    LaunchedEffect(resumeRequest) { refresh() }
    return missing
}

@Composable
internal fun ScopeWarningCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    CouixCard(backgroundColor = Color(0xFFFFBE0A).copy(alpha = 0.18f)) {
        CouixCategoryRow(
            icon = null,
            title = context.getString(R.string.ui_scope_missing),
            onClick = {
                if (!opening) {
                    opening = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { launchApp(context, lsposedLaunchCmd()) } }
                        finally { opening = false }
                    }
                }
            },
        )
    }
}
