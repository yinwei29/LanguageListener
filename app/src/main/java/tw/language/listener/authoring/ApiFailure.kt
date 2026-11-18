package tw.language.listener.authoring

import kotlinx.serialization.json.*

/** Only allowlisted classifications leave this parser. Raw server messages may echo secrets. */
data class ApiFailure(val hint: String,val parameter: String?=null) {
    companion object {
        private val parameters=setOf("model","response_format","text.format","max_tokens","max_completion_tokens","max_output_tokens","temperature","messages","input","generationConfig.responseMimeType","generationConfig.responseSchema","generationConfig.maxOutputTokens")
        fun parse(status: Int,body: String): ApiFailure {
            val error=runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
            val codes=mutableSetOf<String>()
            listOf("code","type","status").forEach { key -> runCatching { error?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()?.let { codes+=it } }
            runCatching { error?.get("details")?.jsonArray.orEmpty().forEach { detail -> detail.jsonObject["reason"]?.jsonPrimitive?.contentOrNull?.let { codes+=it } } }
            val message=runCatching { error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty().lowercase() }.getOrDefault("")
            val parameter=runCatching { error?.get("param")?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it in parameters }
            val hint=when {
                "API_KEY_INVALID" in codes || "invalid_api_key" in codes || status==401 || "api key not valid" in message || "incorrect api key" in message -> "API 金鑰無效或已失效；請確認金鑰屬於目前選取的供應商，再重新儲存"
                "insufficient_quota" in codes || "billing_hard_limit_reached" in codes -> "API 額度不足或未啟用計費；請到供應商的 API 帳戶檢查用量與計費"
                "model_not_found" in codes || status==404 -> "找不到模型或帳號無權使用；請重新載入模型清單並選擇可用模型"
                "unsupported_parameter" in codes || "unsupported_value" in codes || parameter!=null -> "模型不接受目前參數；請切換 API 格式或輸出格式後重試"
                "location is not supported" in message || "free tier is not available" in message || "FAILED_PRECONDITION" in codes -> "此帳號或地區尚未啟用這項 API；請檢查供應商的使用資格與計費設定"
                status==429 || "RESOURCE_EXHAUSTED" in codes -> "API 請求過於頻繁或額度已用完；請檢查用量，稍後再手動繼續"
                status==403 || "PERMISSION_DENIED" in codes -> "API 權限不足；請確認金鑰限制、服務啟用狀態與模型權限"
                status==400 -> "AI 服務拒絕請求；請確認供應商、模型及 API／輸出格式，也可先以 10 筆測試"
                else -> "AI 服務暫時無法完成請求；已完成的草稿保留，請稍後手動繼續"
            }
            return ApiFailure(hint,parameter)
        }
    }
}
