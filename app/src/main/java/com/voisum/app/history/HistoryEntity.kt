package com.voisum.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val detectedLanguage: String = "",
    val audioFilePath: String = "",
    val aiOutputText: String = "",
    val providerUsed: String = "",
    val presetUsed: String = "",
    val pasteSucceeded: Boolean = false
)
