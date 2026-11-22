package tw.language.listener.authoring

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tw.language.listener.data.*
import java.util.UUID

/** The app, not the model, assigns source identity. No source exists before reviewed publication. */
class AiSourceDrafts(private val repo: Repository) {
    companion object {
        fun pending(name: String,request: AiRequest,profile: AiProfile): AiDraft {
            val cleaned=name.trim()
            require(cleaned.isNotEmpty() && cleaned.length<=100 && cleaned.none(Char::isISOControl)) { "請輸入 1–100 字的來源名稱" }
            profile.validate()
            val source=Source(UUID.randomUUID().toString(),cleaned,"",access="local")
            val target=request.copy(sourceId=source.sourceId);target.validate()
            return AiDraft(request=target,items=emptyList(),generationComplete=false,generatedCount=0,generationProfile=profile,pendingSource=source)
        }
        fun validate(value: AiDraft) {
            value.request.validate()
            require(value.items.size<=value.request.count && value.items.map { it.itemId }.distinct().size==value.items.size)
            value.items.forEach { AuthoringValidation.validate(it,value.request) }
            val source=checkNotNull(value.pendingSource) { "沒有待建立的 AI 來源" }
            UUID.fromString(source.sourceId)
            require(source.sourceId==value.request.sourceId && source.access=="local" && source.url.isEmpty() && source.name.isNotBlank() && source.name.length<=100 && source.name.none(Char::isISOControl)) { "AI 來源草稿不合法" }
        }
    }
    suspend fun saveCheckpoint(value: AiDraft,expectedEpoch: Long=repo.syncEpoch.get())=repo.mutation.withLock {
        check(repo.syncEpoch.get()==expectedEpoch) { "來源或備份狀態已改變，尚未寫入，請重新審核" }
        validate(value)
        check(repo.dao.sources().none { it.sourceId==value.request.sourceId }) { "此來源已建立或被變更，請重新開啟審核結果" }
        repo.dao.putDraft(DraftRecord(payload=JsonCodec.json.encodeToString(value)))
    }
    suspend fun publish(value: AiDraft,selected: List<Item>): AiDraft=repo.mutation.withLock {
        validate(value);AuthoringValidation.validateReview(value,selected)
        repo.db.withTransaction {
            val persisted=checkNotNull(repo.dao.observeDraftSnapshot()) { "草稿已移除，未建立來源" }
            val saved=JsonCodec.json.decodeFromString<AiDraft>(persisted.payload)
            check(saved.draftId==value.draftId && saved.request==value.request && saved.pendingSource==value.pendingSource) { "草稿已變更，未建立來源" }
            if(saved.written) {
                check(repo.dao.sources().any { it.sourceId==value.request.sourceId && it.access in listOf("local","file") }) { "來源已移除，未重新建立" }
                saved
            } else {
                check(repo.dao.sources().none { it.sourceId==value.request.sourceId }) { "此來源已存在" }
                check(saved.items.map { it.itemId }.toSet()==value.items.map { it.itemId }.toSet()) { "草稿已變更，未建立來源" }
                val published=value.copy(items=value.items.map { old -> selected.find { it.itemId==old.itemId } ?: old },selectedIds=selected.map { it.itemId },written=true,writtenCount=selected.size,skippedCount=0)
                repo.dao.putSources(listOf(checkNotNull(value.pendingSource)))
                repo.dao.putItems(selected)
                repo.dao.putDraft(DraftRecord(payload=JsonCodec.json.encodeToString(published)))
                repo.syncEpoch.incrementAndGet()
                published
            }
        }
    }
}
