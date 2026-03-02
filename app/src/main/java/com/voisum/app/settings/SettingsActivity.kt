package com.voisum.app.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.material.color.DynamicColors
import com.voisum.app.api.AiProviderRouter
import com.voisum.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context) }
    val router = remember { AiProviderRouter(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedProvider by remember { mutableStateOf(prefs.aiProvider) }
    var selectedModel by remember { mutableStateOf(prefs.getModelForProvider()) }
    var apiKey by remember { mutableStateOf(prefs.getApiKeyForProvider()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var whisperApiKey by remember { mutableStateOf(prefs.openAiApiKey) }
    var whisperKeyVisible by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }

    var activePresetId by remember { mutableStateOf(prefs.activePresetId) }
    var presets by remember { mutableStateOf(prefs.presets) }
    var editingPreset by remember { mutableStateOf<PromptPreset?>(null) }

    var perAppEnabled by remember { mutableStateOf(prefs.perAppRulesEnabled) }
    var showPerAppScreen by remember { mutableStateOf(false) }

    var bubbleSide by remember { mutableStateOf(prefs.bubbleSide) }
    var autoDismiss by remember { mutableStateOf(prefs.autoDismissAfterPaste) }
    var maxDuration by remember { mutableIntStateOf(prefs.maxRecordingDurationSeconds) }

    var languageFlags by remember { mutableStateOf(prefs.languageFlags) }
    var showAddFlag by remember { mutableStateOf(false) }

    if (showPerAppScreen) {
        PerAppRulesScreen(
            prefs = prefs,
            onBack = { showPerAppScreen = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- AI Provider ---
            item {
                SectionHeader("AI Provider")
            }

            item {
                val providers = AiProvider.entries.toList()
                val selectedIndex = providers.indexOf(selectedProvider)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    providers.forEachIndexed { index, provider ->
                        SegmentedButton(
                            selected = index == selectedIndex,
                            onClick = {
                                selectedProvider = provider
                                prefs.aiProvider = provider
                                apiKey = prefs.getApiKeyForProvider(provider)
                                selectedModel = prefs.getModelForProvider(provider)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, providers.size)
                        ) {
                            Text(
                                provider.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            item {
                // --- Model version picker ---
                val modelOptions = AvailableModels.forProvider(selectedProvider)
                val currentLabel = modelOptions.find { it.id == selectedModel }?.label ?: selectedModel
                var modelMenuExpanded by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        label = { Text("Model") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { modelMenuExpanded = true },
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        trailingIcon = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Select model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    // Invisible overlay to capture clicks on the disabled field
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { modelMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(option.label, modifier = Modifier.weight(1f))
                                        if (option.id == selectedModel) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedModel = option.id
                                    prefs.setModelForProvider(option.id, selectedProvider)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                val label = when (selectedProvider) {
                    AiProvider.GEMINI -> "Gemini API Key"
                    AiProvider.CHATGPT -> "OpenAI API Key"
                    AiProvider.GITHUB_COPILOT -> "GitHub Token"
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        prefs.setApiKeyForProvider(it, selectedProvider)
                    },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                "Toggle visibility"
                            )
                        }
                    }
                )
            }

            // Extra field: OpenAI API key for Whisper when GitHub Copilot is selected
            if (selectedProvider == AiProvider.GITHUB_COPILOT) {
                item {
                    OutlinedTextField(
                        value = whisperApiKey,
                        onValueChange = {
                            whisperApiKey = it
                            prefs.openAiApiKey = it
                        },
                        label = { Text("OpenAI API Key (for transcription)") },
                        supportingText = {
                            Text("Required for voice-to-text. Get a free key at platform.openai.com")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (whisperKeyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { whisperKeyVisible = !whisperKeyVisible }) {
                                Icon(
                                    if (whisperKeyVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    "Toggle visibility"
                                )
                            }
                        }
                    )
                }
            }

            item {
                FilledIconButton(
                    onClick = {
                        testingConnection = true
                        scope.launch {
                            val result = router.testConnection(selectedProvider, apiKey)
                            testingConnection = false
                            result.fold(
                                onSuccess = {
                                    snackbarHostState.showSnackbar("Connection successful")
                                },
                                onFailure = { error ->
                                    snackbarHostState.showSnackbar(
                                        error.message ?: "Connection failed"
                                    )
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apiKey.isNotBlank() && !testingConnection
                ) {
                    Text(if (testingConnection) "Testing..." else "Test connection")
                }
            }

            // --- Prompts ---
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Prompts")
            }

            items(presets) { preset ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activePresetId = preset.id
                            prefs.activePresetId = preset.id
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(parseColor(preset.colorHex))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${preset.promptBody.length} chars",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (preset.id == activePresetId) {
                            Icon(
                                Icons.Default.Check,
                                "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { editingPreset = preset }) {
                            Text("Edit")
                        }
                    }
                }
            }

            // --- Per-App Rules ---
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Per-App Rules")
            }

            item {
                ListItem(
                    headlineContent = { Text("Enable per-app rules") },
                    trailingContent = {
                        Switch(
                            checked = perAppEnabled,
                            onCheckedChange = {
                                perAppEnabled = it
                                prefs.perAppRulesEnabled = it
                            }
                        )
                    }
                )
            }

            item {
                AnimatedVisibility(visible = perAppEnabled) {
                    TextButton(onClick = { showPerAppScreen = true }) {
                        Text("Manage app mappings")
                    }
                }
            }

            // --- Bubble Behavior ---
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Bubble Behavior")
            }

            item {
                val sides = listOf("left", "right")
                val selectedSideIndex = sides.indexOf(bubbleSide)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Side", modifier = Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        sides.forEachIndexed { index, side ->
                            SegmentedButton(
                                selected = index == selectedSideIndex,
                                onClick = {
                                    bubbleSide = side
                                    prefs.bubbleSide = side
                                    prefs.bubbleSnappedSide = side
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, sides.size)
                            ) {
                                Text(side.replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }

            item {
                ListItem(
                    headlineContent = { Text("Auto-dismiss after paste") },
                    trailingContent = {
                        Switch(
                            checked = autoDismiss,
                            onCheckedChange = {
                                autoDismiss = it
                                prefs.autoDismissAfterPaste = it
                            }
                        )
                    }
                )
            }

            item {
                val durationOptions = listOf(30, 60, 120, 180)
                val labels = listOf("30s", "1m", "2m", "3m")
                val selectedDurIndex = durationOptions.indexOf(maxDuration)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Max recording", modifier = Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        durationOptions.forEachIndexed { index, dur ->
                            SegmentedButton(
                                selected = index == selectedDurIndex,
                                onClick = {
                                    maxDuration = dur
                                    prefs.maxRecordingDurationSeconds = dur
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, durationOptions.size)
                            ) {
                                Text(labels[index])
                            }
                        }
                    }
                }
            }

            // --- Language Flags ---
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Language Flag Mapping")
            }

            items(languageFlags) { entry ->
                ListItem(
                    headlineContent = { Text(entry.keyword) },
                    leadingContent = { Text(entry.flag, style = MaterialTheme.typography.headlineSmall) },
                    trailingContent = {
                        IconButton(onClick = {
                            prefs.removeLanguageFlag(entry.keyword)
                            languageFlags = prefs.languageFlags
                        }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                )
            }

            item {
                TextButton(onClick = { showAddFlag = true }) {
                    Text("Add language flag")
                }
            }

            // Bottom spacing
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // --- Edit Preset Dialog ---
    editingPreset?.let { preset ->
        var editName by remember(preset) { mutableStateOf(preset.name) }
        var editPrompt by remember(preset) { mutableStateOf(preset.promptBody) }
        var editColor by remember(preset) { mutableStateOf(preset.colorHex) }

        AlertDialog(
            onDismissRequest = { editingPreset = null },
            title = { Text("Edit Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it.take(14) },
                        label = { Text("Name (max 14 chars)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editColor,
                        onValueChange = { editColor = it },
                        label = { Text("Color hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        label = { Text("Prompt body (${editPrompt.length} chars)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        maxLines = 10
                    )
                    TextButton(onClick = {
                        prefs.resetPresetToDefault(preset.id)
                        presets = prefs.presets
                        editingPreset = null
                    }) {
                        Text("Reset to default")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = preset.copy(
                        name = editName,
                        colorHex = editColor,
                        promptBody = editPrompt
                    )
                    val mutablePresets = presets.toMutableList()
                    val idx = mutablePresets.indexOfFirst { it.id == preset.id }
                    if (idx >= 0) {
                        mutablePresets[idx] = updated
                        prefs.presets = mutablePresets
                        presets = mutablePresets
                    }
                    editingPreset = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPreset = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    // --- Add Language Flag Dialog ---
    if (showAddFlag) {
        var keyword by remember { mutableStateOf("") }
        var flag by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddFlag = false },
            title = { Text("Add Language Flag") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("Language keyword") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = flag,
                        onValueChange = { flag = it },
                        label = { Text("Flag emoji") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (keyword.isNotBlank() && flag.isNotBlank()) {
                            prefs.addLanguageFlag(LanguageFlagEntry(keyword, flag))
                            languageFlags = prefs.languageFlags
                            showAddFlag = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFlag = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color.Gray
    }
}
