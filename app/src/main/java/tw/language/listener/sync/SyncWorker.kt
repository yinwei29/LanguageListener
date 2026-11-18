package tw.language.listener.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import tw.language.listener.ListenerApp
import tw.language.listener.data.*
import java.io.File
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo=(applicationContext as ListenerApp).repository
        val settings=repo.dao.settings() ?: Settings()
        fun networkAllowed(): Boolean {
            if(!settings.wifiOnly) return true
            val cm=applicationContext.getSystemService(ConnectivityManager::class.java)
            return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
        if(!networkAllowed()) return@withContext Result.retry()
        val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()
        var failed=false
        for(source in repo.dao.sources().filter { it.url.isNotEmpty() && it.access!="file" }) {
            val epoch=repo.syncEpoch.get()
            val temporary=File.createTempFile("sheet", ".csv",applicationContext.cacheDir)
            try {
                if(source.access=="google") {
                    val app=applicationContext as ListenerApp
                    try { app.sheets.download(source,app.google.token(),temporary,::networkAllowed) }
                    catch(e: tw.language.listener.authoring.RemoteError) { if(e.status==401) app.google.clear(); throw e }
                } else downloadSheet(client,SheetUrl.normalize(source.url),temporary,::networkAllowed)
                if(!networkAllowed()) return@withContext Result.retry()
                temporary.bufferedReader(Charsets.UTF_8).use { SheetImporter(repo).import(source,it,epoch) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(!networkAllowed()) return@withContext Result.retry()
                if(repo.syncEpoch.get()!=epoch) continue
                failed=true
                repo.mutation.withLock {
                    repo.dao.sources().find { it==source && repo.syncEpoch.get()==epoch }?.let {
                        repo.dao.putSources(listOf(it.copy(error=(e.message ?: "同步失敗").take(300))))
                    }
                }
            } finally { temporary.delete() }
        }
        if(failed) Result.failure() else Result.success()
    }
    companion object {
        const val WORK_NAME="sheets-sync"
        fun enqueue(context: Context, wifiOnly: Boolean, replace: Boolean=false) {
            val constraints=Constraints.Builder().setRequiredNetworkType(if(wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints)
                    .addTag("scheduled-at:${System.currentTimeMillis()}")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
        }
    }
}
