package tw.language.listener.authoring

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

class RemoteError(val status: Int, service: String) : IllegalStateException("$service：HTTP $status，請檢查授權、API 設定或配額")

class JsonHttp(private val client: OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
    .readTimeout(90,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()) {
    suspend fun request(url: HttpUrl, service: String, headers: Map<String,String>, method: String="GET", body: JsonElement?=null): JsonObject = withContext(Dispatchers.IO) {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "API 僅接受不含帳密的 HTTPS 網址" }
        val builder=Request.Builder().url(url)
        headers.forEach { (name,value) -> builder.header(name,value) }
        if(method!="GET") {
            val bytes=(body ?: JsonObject(emptyMap())).toString().toByteArray(Charsets.UTF_8)
            builder.method(method,object: RequestBody() {
                override fun contentType()="application/json; charset=utf-8".toMediaType()
                override fun contentLength()=bytes.size.toLong()
                override fun isOneShot()=true // Never replay append/generation after an ambiguous response.
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            })
        }
        val call=client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build().newCall(builder.build())
        coroutineScope {
            val cancellation=launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if(!response.isSuccessful) throw RemoteError(response.code,service)
                    val data=checkNotNull(response.body).byteStream().use { input ->
                        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            val size=input.read(buffer); if(size<0) break
                            check(output.size()+size<=8*1024*1024) { "$service 回應過大，請減少每批資料" }
                            output.write(buffer,0,size)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    }
                    Json.parseToJsonElement(data).jsonObject
                }
            } catch(e: Exception) { currentCoroutineContext().ensureActive(); throw e }
            finally { cancellation.cancel() }
        }
    }
}
