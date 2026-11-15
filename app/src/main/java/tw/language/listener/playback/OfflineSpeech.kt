package tw.language.listener.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.language.listener.data.*
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OfflineSpeech(private val context: Context) {
    private val initialized=CompletableDeferred<Boolean>()
    private val pending=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
    private val mutex=Mutex()
    private val engine: TextToSpeech = TextToSpeech(context) { status -> initialized.complete(status==TextToSpeech.SUCCESS) }
    init {
        engine.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { pending.remove(id)?.complete(Unit) }
            @Deprecated("Android callback") override fun onError(id: String?) { pending.remove(id)?.completeExceptionally(IllegalStateException("離線 TTS 合成失敗")) }
            override fun onError(id: String?, errorCode: Int) { pending.remove(id)?.completeExceptionally(IllegalStateException("離線 TTS 合成失敗（$errorCode）")) }
        })
    }
    private suspend fun ready() {
        check(withTimeout(15_000) { initialized.await() }) { "系統 TTS 無法啟動，請安裝語音引擎" }
    }
    private fun offlineVoice(tag: String): Voice? {
        val locale=Locale.forLanguageTag(tag)
        return engine.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && it.locale.language==locale.language &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedWith(compareByDescending<Voice> { it.locale.country==locale.country }.thenByDescending { it.quality }).firstOrNull()
    }
    suspend fun checkVoices(): Map<String,Boolean> = withContext(Dispatchers.Main) {
        ready(); PlaybackOptions.LANGUAGES.associateWith { offlineVoice(it)!=null }
    }
    suspend fun render(item: Item, options: PlaybackOptions): File = mutex.withLock {
        ready(); options.validate()
        val dir=File(context.cacheDir,"speech").apply { mkdirs() }
        val voices=withContext(Dispatchers.Main) {
            options.languages().joinToString { language ->
                checkNotNull(offlineVoice(language)) { "缺少 $language 離線語音包，請至設定安裝" }.name
            }
        }
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(
            (voices+JsonCodec.json.encodeToString(Item.serializer(),item)+JsonCodec.json.encodeToString(PlaybackOptions.serializer(),options)).toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val target=File(dir,"$fingerprint.wav")
        if(target.exists()) { target.setLastModified(System.currentTimeMillis()); return@withLock target }
        // Only a bounded audio cache is retained. Downloaded text in Room is never evicted.
        var size=dir.listFiles().orEmpty().sumOf { it.length() }
        for(file in dir.listFiles().orEmpty().sortedBy { it.lastModified() }) {
            if(size<64L*1024*1024) break
            size-=file.length(); file.delete()
        }
        val temporary=File(dir,"$fingerprint.part")
        try {
            RandomAccessFile(temporary,"rw").use { output ->
                output.setLength(0); output.write(PcmWave.header(0))
                repeat(options.repeats) {
                    for(language in options.languages()) {
                        val text=item.text(language)
                        require(text.isNotBlank()) { "此教材缺少 $language 文字" }
                        for(chunk in text.chunked(TextToSpeech.getMaxSpeechInputLength()-1)) {
                            val raw=File.createTempFile("utterance", ".wav",dir)
                            try { synthesize(chunk,language,options.rate(language),raw); PcmWave.append(raw,output) }
                            finally { raw.delete() }
                        }
                        PcmWave.silence(output,options.gapSeconds)
                        require(output.length()<64L*1024*1024) { "單筆音訊過長，請降低重複或間隔" }
                    }
                }
                val bytes=(output.length()-44).toInt(); output.seek(0); output.write(PcmWave.header(bytes))
            }
            check(temporary.renameTo(target)) { "無法儲存離線音訊" }
            target
        } finally { temporary.delete() }
    }
    private suspend fun synthesize(text: String, language: String, rate: Float, file: File) {
        val id=UUID.randomUUID().toString()
        val completion=CompletableDeferred<Unit>()
        pending[id]=completion
        try {
            withContext(Dispatchers.Main) {
                val voice=checkNotNull(offlineVoice(language)) { "缺少 $language 離線語音包，請至設定安裝；無法使用網路語音替代" }
                check(engine.setVoice(voice)==TextToSpeech.SUCCESS) { "無法啟用 $language 離線語音" }
                check(engine.setSpeechRate(rate)==TextToSpeech.SUCCESS)
                check(engine.synthesizeToFile(text,null,file,id)==TextToSpeech.SUCCESS) { "TTS 引擎拒絕合成" }
            }
            withTimeout(60_000) { completion.await() }
        } finally { pending.remove(id) }
    }
}
