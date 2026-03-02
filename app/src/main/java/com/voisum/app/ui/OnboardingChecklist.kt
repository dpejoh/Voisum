package com.voisum.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voisum.app.settings.SettingsActivity
import com.voisum.app.utils.PermissionHelper

@Composable
fun OnboardingChecklist(
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    var accessibilityEnabled by remember {
        mutableStateOf(PermissionHelper.isAccessibilityEnabled(context))
    }
    var overlayEnabled by remember {
        mutableStateOf(PermissionHelper.isOverlayPermissionGranted(context))
    }
    var micEnabled by remember {
        mutableStateOf(PermissionHelper.isMicrophonePermissionGranted(context))
    }
    var apiConfigured by remember {
        mutableStateOf(PermissionHelper.isApiKeyConfigured(context))
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micEnabled = granted
        onRefresh()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChecklistItem(
            icon = Icons.Default.Accessibility,
            title = "Accessibility Service",
            enabled = accessibilityEnabled,
            onClick = {
                PermissionHelper.openAccessibilitySettings(context)
            }
        )

        ChecklistItem(
            icon = Icons.Default.Layers,
            title = "Overlay Permission",
            enabled = overlayEnabled,
            onClick = {
                PermissionHelper.openOverlayPermissionSettings(context)
            }
        )

        ChecklistItem(
            icon = Icons.Default.Mic,
            title = "Microphone",
            enabled = micEnabled,
            onClick = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        ChecklistItem(
            icon = Icons.Default.Api,
            title = "AI Provider and Key",
            enabled = apiConfigured,
            onClick = {
                val intent = android.content.Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistItem(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.width(16.dp))

            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            Icon(
                if (enabled) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (enabled) "Complete" else "Incomplete",
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
