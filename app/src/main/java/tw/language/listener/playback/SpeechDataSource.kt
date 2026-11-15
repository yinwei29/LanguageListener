package tw.language.listener.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tw.language.listener.data.*
import java.io.IOException

/** ExoPlayer invokes open on its loader thread, keeping synthesis off the UI thread. */
@UnstableApi
class SpeechDataSource(private val repository: Repository, private val speech: OfflineSpeech,
    private val options: PlaybackOptions) : DataSource {
    private val file=FileDataSource()
    private var requested: Uri?=null
    override fun addTransferListener(listener: TransferListener) { file.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        try {
            requested=dataSpec.uri
            val source=requireNotNull(dataSpec.uri.getQueryParameter("source"))
            val id=requireNotNull(dataSpec.uri.getQueryParameter("item"))
            val audio=runBlocking(Dispatchers.IO) {
                val item=checkNotNull(repository.dao.item(source,id)) { "離線教材尚未下載，請先同步來源" }
                speech.render(item,options)
            }
            return file.open(dataSpec.withUri(Uri.fromFile(audio)))
        } catch(e: Exception) { throw IOException(e.message ?: "離線語音無法播放",e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int)=file.read(buffer,offset,length)
    override fun getUri(): Uri?=requested
    override fun close() { file.close() }
}
