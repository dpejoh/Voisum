package com.voisum.app.settings

import android.content.pm.ApplicationInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppRulesScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(prefs.perAppRules) }
    var showAddDialog by remember { mutableStateOf(false) }
    val presetList = prefs.presets

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-App Rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add mapping")
            }
        }
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No per-app rules configured",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rules, key = { it.packageName }) { rule ->
                    val preset = presetList.find { it.id == rule.presetId }
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                prefs.removePerAppRule(rule.packageName)
                                rules = prefs.perAppRules
                                true
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color by animateColorAsState(
                                when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                                    else -> Color.Transparent
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(color)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    "Delete",
                                    tint = MaterialTheme.colorScheme.onError
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false
                    ) {
                        ElevatedCard(shape = RoundedCornerShape(12.dp)) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        getAppLabel(context, rule.packageName),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        rule.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (preset != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(parseColor(preset.colorHex))
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                preset.name,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Add Mapping Dialog ---
    if (showAddDialog) {
        var packageName by remember { mutableStateOf("") }
        var selectedPresetId by remember { mutableStateOf(presetList.firstOrNull()?.id ?: "") }
        var presetDropdownExpanded by remember { mutableStateOf(false) }

        // Get installed apps for picker
        val installedApps = remember {
            try {
                context.packageManager
                    .getInstalledApplications(0)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
            } catch (_: Exception) {
                emptyList()
            }
        }
        var appDropdownExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Per-App Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // App picker
                    ExposedDropdownMenuBox(
                        expanded = appDropdownExpanded,
                        onExpandedChange = { appDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (packageName.isNotBlank())
                                getAppLabel(context, packageName) else "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("App") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(appDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = appDropdownExpanded,
                            onDismissRequest = { appDropdownExpanded = false }
                        ) {
                            installedApps.take(50).forEach { appInfo ->
                                val label = context.packageManager
                                    .getApplicationLabel(appInfo).toString()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(label)
                                            Text(
                                                appInfo.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        packageName = appInfo.packageName
                                        appDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Preset picker
                    ExposedDropdownMenuBox(
                        expanded = presetDropdownExpanded,
                        onExpandedChange = { presetDropdownExpanded = it }
                    ) {
                        val selectedPreset = presetList.find { it.id == selectedPresetId }
                        OutlinedTextField(
                            value = selectedPreset?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Preset") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(presetDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = presetDropdownExpanded,
                            onDismissRequest = { presetDropdownExpanded = false }
                        ) {
                            presetList.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        selectedPresetId = preset.id
                                        presetDropdownExpanded = false
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(parseColor(preset.colorHex))
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (packageName.isNotBlank() && selectedPresetId.isNotBlank()) {
                            prefs.addPerAppRule(PerAppRule(packageName, selectedPresetId))
                            rules = prefs.perAppRules
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

private fun getAppLabel(context: android.content.Context, packageName: String): String {
    return try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: Exception) {
        packageName
    }
}

private fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color.Gray
    }
}
