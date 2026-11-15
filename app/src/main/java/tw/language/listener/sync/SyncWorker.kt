package tw.language.listener.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.language.listener.ListenerApp
import tw.language.listener.data.*
import java.io.File
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo=(applicationContext as ListenerApp).repository
        val settings=repo.dao.settings() ?: Settings()
        if(settings.wifiOnly) {
            val cm=applicationContext.getSystemService(ConnectivityManager::class.java)
            if(cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)!=true) return@withContext Result.retry()
        }
        val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()
        var failed=false
        for(source in repo.dao.sources().filter { it.url.isNotEmpty() }) {
            val temporary=File.createTempFile("sheet", ".csv",applicationContext.cacheDir)
            try {
                client.newCall(Request.Builder().url(SheetUrl.normalize(source.url)).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}；請確認教材已公開或發布為 CSV" }
                    check(response.request.url.isHttps) { "拒絕不安全的重新導向" }
                    val body=checkNotNull(response.body)
                    check(body.contentType()?.subtype?.contains("html")!=true) { "來源傳回登入頁面，請公開或發布教材" }
                    temporary.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
                }
                temporary.bufferedReader(Charsets.UTF_8).use { SheetImporter(repo).import(source,it) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                failed=true
                repo.mutation.withLock {
                    repo.dao.sources().find { it.sourceId==source.sourceId }?.let {
                        repo.dao.putSources(listOf(it.copy(error=(e.message ?: "同步失敗").take(300))))
                    }
                }
            } finally { temporary.delete() }
        }
        if(failed) Result.failure() else Result.success()
    }
    companion object {
        fun enqueue(context: Context, wifiOnly: Boolean) {
            val constraints=Constraints.Builder().setRequiredNetworkType(if(wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sheets-sync",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build())
        }
    }
}
