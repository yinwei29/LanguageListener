package tw.language.listener.sync

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Cancellation closes the HTTP call even while execute/read is blocked on a slow server. */
internal suspend fun downloadSheet(client: OkHttpClient, url: String, target: File,
    networkAllowed: ()->Boolean = { true }) = withContext(Dispatchers.IO) {
    coroutineScope {
        val request=Request.Builder().url(url).build()
        require(request.url.isHttps) { "同步僅允許 HTTPS" }
        val call=client.newBuilder().followSslRedirects(false).build().newCall(request)
        val cancellation=launch(start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            check(networkAllowed()) { "目前網路不符合同步政策" }
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}；請確認教材已公開或發布為 CSV" }
                check(response.request.url.isHttps) { "拒絕不安全的重新導向" }
                val body=checkNotNull(response.body)
                check(body.contentType()?.subtype?.contains("html")!=true) { "來源傳回登入頁面，請公開或發布教材" }
                target.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        check(networkAllowed()) { "網路已變更，同步已停止；保留原有教材" }
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(buffer,0,count)
                    }
                } }
            }
            currentCoroutineContext().ensureActive()
        } catch(e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally { cancellation.cancel() }
    }
}
