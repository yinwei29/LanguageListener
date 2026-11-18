package tw.language.listener.authoring

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tw.language.listener.data.Item

/** Planned small requests; a failed request is never retried automatically. */
class AiBatchGenerator(private val generator: AiGenerator=AiGenerator()) {
    suspend fun generate(profile: AiProfile,key: String,request: AiRequest,resume: AiDraft?=null,
        checkpoint: suspend (AiDraft)->Unit,existing: suspend (List<Item>)->Set<String> = { emptySet() }): AiDraft {
        request.validate();profile.validate()
        require(resume==null || (!resume.written && !resume.generationComplete && resume.request==request && resume.generationProfile==profile && resume.generatedCount in 0 until request.count)) { "草稿或 AI 設定已改變，請切回原設定繼續，或重新產生" }
        var aggregate=resume ?: AiDraft(request=request,items=emptyList(),generationComplete=false,generatedCount=0,generationProfile=profile)
        val fingerprints=aggregate.items.map(AuthoringValidation::fingerprint).toMutableSet()
        while(aggregate.generatedCount<request.count) {
            currentCoroutineContext().ensureActive()
            val size=minOf(10,request.count-aggregate.generatedCount)
            val batch=generator.generate(profile,key,request.copy(count=size),aggregate.items)
            withContext(NonCancellable) {
                val duplicates=existing(batch.items)
                val fresh=batch.items.filter { item -> AuthoringValidation.fingerprint(item).let { it !in duplicates && fingerprints.add(it) } }
                val done=aggregate.generatedCount+size
                aggregate=aggregate.copy(items=aggregate.items+fresh,generatedCount=done,generationComplete=done==request.count,
                    removedDuplicates=aggregate.removedDuplicates+batch.removedDuplicates+batch.items.size-fresh.size)
                checkpoint(aggregate)
            }
        }
        return aggregate
    }
}
