package tw.language.listener.authoring

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class AiModel(val id: String,val label: String=id)
data class AiModelList(val origin: String,val items: List<AiModel>)
class AiModels(private val http: JsonHttp=JsonHttp(),private val testBase: HttpUrl?=null) {
    suspend fun load(profile: AiProfile,key: String): List<AiModel> {
        profile.copy(model=profile.model.ifBlank { "model-discovery" }).validate();require(key.length in 1..4096 && key.all { it.code in 33..126 }) { "請先輸入或儲存此供應商的 API 金鑰" }
        val base=testBase ?: profile.endpoint.trimEnd('/').toHttpUrl()
        val headers=if(profile.provider=="gemini") mapOf("x-goog-api-key" to key) else mapOf("Authorization" to "Bearer $key")
        val found=linkedMapOf<String,AiModel>();val tokens=mutableSetOf<String>();var token: String?=null
        do {
            currentCoroutineContext().ensureActive()
            val url=base.newBuilder().addPathSegment("models").apply {
                if(profile.provider=="gemini") { addQueryParameter("pageSize","100");token?.let { addQueryParameter("pageToken",it) } }
            }.build()
            val response=http.request(url,"AI 模型清單",headers)
            val values=response[if(profile.provider=="gemini") "models" else "data"]?.jsonArray
            checkNotNull(values) { "模型清單格式不符；可手動輸入模型 ID" }
            values.forEach { value ->
                val obj=value.jsonObject
                val id=obj[if(profile.provider=="gemini") "name" else "id"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@forEach
                if(id.length>100 || id.isBlank() || id.any(Char::isISOControl)) return@forEach
                if(profile.provider=="gemini" && obj["supportedGenerationMethods"]?.jsonArray.orEmpty().none { it.jsonPrimitive.content=="generateContent" }) return@forEach
                if(profile.provider!="compatible" && Regex("image|audio|realtime|embedding|tts|transcrib|robotics|computer-use|search|deep-research",RegexOption.IGNORE_CASE).containsMatchIn(id)) return@forEach
                if(profile.provider=="openai" && !id.startsWith("gpt-") && !id.matches(Regex("o[0-9].*"))) return@forEach
                if(profile.provider=="gemini" && !id.matches(Regex("[A-Za-z0-9._-]+"))) return@forEach
                found[id]=AiModel(id)
            }
            token=if(profile.provider=="gemini") response["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) else null
            check(token==null || (tokens.add(token!!) && tokens.size<=50)) { "模型分頁回應異常；可手動輸入模型 ID" }
        } while(token!=null)
        check(found.isNotEmpty()) { "帳號未回傳適合的文字模型；可手動輸入模型 ID 並檢查權限" }
        return found.values.sortedBy { it.id }
    }
    companion object {
        fun examples(provider: String)=when(provider) {
            "gemini"->listOf("gemini-2.5-flash","gemini-2.5-pro")
            "openai"->listOf("gpt-4.1-mini","gpt-4.1","gpt-4o-mini","gpt-5-mini")
            else->emptyList()
        }.map(::AiModel)
    }
}
