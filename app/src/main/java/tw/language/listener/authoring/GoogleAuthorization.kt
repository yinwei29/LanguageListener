package tw.language.listener.authoring

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import tw.language.listener.data.JsonCodec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class GoogleSession(val token: String, val expiresAt: Long, val writable: Boolean)

class GoogleAuthorization(context: Context, private val vault: SecretVault) {
    private val client=Identity.getAuthorizationClient(context)
    suspend fun authorize(write: Boolean): AuthorizationResult = client.authorize(AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(if(write) WRITE_SCOPE else READ_SCOPE))).build()).awaitResult()
    fun fromIntent(intent: Intent): AuthorizationResult=client.getAuthorizationResultFromIntent(intent)
    fun accept(result: AuthorizationResult, requireWrite: Boolean=false) {
        check(!result.hasResolution()) { "Google 授權尚未完成" }
        val token=checkNotNull(result.accessToken) { "未取得 Google 授權" }
        val scopes=result.grantedScopes
        requireSheetScopes(scopes,requireWrite)
        vault.put("google-session",JsonCodec.json.encodeToString(GoogleSession(token,System.currentTimeMillis()+45*60*1000L,WRITE_SCOPE in scopes)))
        vault.remove("google-disabled")
    }
    suspend fun token(write: Boolean=false): String {
        check(vault.get("google-disabled")!="true") { "Google 同步授權已在本機停用，請在教材庫重新連接" }
        val cached=vault.get("google-session")?.let { encoded ->
            try { JsonCodec.json.decodeFromString<GoogleSession>(encoded) }
            catch(_: Exception) { clear(); throw IllegalStateException("本機 Google 憑證格式已失效，請重新授權") }
        }
        if(cached!=null && cached.expiresAt>System.currentTimeMillis() && (!write || cached.writable)) return cached.token
        val result=authorize(write)
        check(!result.hasResolution()) { "需要開啟教材庫重新授權 Google Sheets" }
        accept(result,write)
        return checkNotNull(result.accessToken)
    }
    fun clear() { vault.remove("google-session"); vault.put("google-disabled","true") }
    companion object {
        const val WRITE_SCOPE="https://www.googleapis.com/auth/spreadsheets"
        const val READ_SCOPE="https://www.googleapis.com/auth/spreadsheets.readonly"
    }
}

internal fun requireSheetScopes(scopes: List<String>, write: Boolean) {
    check(GoogleAuthorization.WRITE_SCOPE in scopes || (!write && GoogleAuthorization.READ_SCOPE in scopes)) {
        if(write) "未授予 Sheets 寫入權限，尚未初始化或寫入" else "未授予 Sheets 讀取權限"
    }
}

private suspend fun <T> Task<T>.awaitResult(): T=suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
