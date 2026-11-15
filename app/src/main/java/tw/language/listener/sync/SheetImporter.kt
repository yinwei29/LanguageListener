package tw.language.listener.sync

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import tw.language.listener.data.*
import java.io.Reader

/** A complete snapshot becomes visible atomically; progress is never part of this transaction. */
class SheetImporter(private val repo: Repository) {
    suspend fun import(source: Source, input: Reader) = repo.mutation.withLock {
        if(repo.dao.sources().none { it.sourceId==source.sourceId }) return@withLock
        repo.db.withTransaction {
            val csv=CsvReader(input)
            val columns=SheetColumns(requireNotNull(csv.row()) { "CSV 為空" })
            repo.dao.softDeleteSource(source.sourceId)
            val batch=mutableListOf<Item>()
            while(true) {
                val row=csv.row() ?: break
                if(row.all { it.isBlank() }) continue
                batch+=columns.item(source.sourceId,row)
                if(batch.size==250) { repo.dao.putItems(batch.toList()); batch.clear() }
            }
            if(batch.isNotEmpty()) repo.dao.putItems(batch)
            repo.dao.putSources(listOf(source.copy(lastSync=System.currentTimeMillis(),error=null)))
        }
    }
}
