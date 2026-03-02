package com.voisum.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    // Cards and list items: 12dp
    small = RoundedCornerShape(12.dp),
    // General medium components
    medium = RoundedCornerShape(12.dp),
    // Bottom sheets, dialogs: 28dp top corners
    large = RoundedCornerShape(28.dp),
    // Extra large (full sheets)
    extraLarge = RoundedCornerShape(28.dp),
)
