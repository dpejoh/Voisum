package com.voisum.app.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class HistoryRepository(context: Context) {

    private val dao = HistoryDatabase.getInstance(context).historyDao()

    companion object {
        private const val MAX_RECORDS = 20
    }

    fun getAllFlow(): Flow<List<HistoryEntity>> = dao.getAllFlow()

    suspend fun getAll(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getById(id: Long): HistoryEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun insert(entity: HistoryEntity): Long = withContext(Dispatchers.IO) {
        val newId = dao.insert(entity)
        enforceMaxRecords()
        newId
    }

    suspend fun delete(entity: HistoryEntity) = withContext(Dispatchers.IO) {
        deleteAudioFile(entity.audioFilePath)
        dao.delete(entity)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val all = dao.getAll()
        for (record in all) {
            deleteAudioFile(record.audioFilePath)
        }
        dao.deleteAll()
    }

    private suspend fun enforceMaxRecords() {
        while (dao.count() > MAX_RECORDS) {
            val oldest = dao.getOldest() ?: break
            deleteAudioFile(oldest.audioFilePath)
            dao.delete(oldest)
        }
    }

    private fun deleteAudioFile(path: String) {
        if (path.isNotBlank()) {
            try {
                File(path).delete()
            } catch (_: Exception) {
                // File may already be deleted
            }
        }
    }
}
