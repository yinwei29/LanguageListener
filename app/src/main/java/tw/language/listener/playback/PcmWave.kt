package tw.language.listener.playback

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Normalizes installed TTS engines' PCM WAV output to mono 22.05 kHz, 16-bit. */
object PcmWave {
    const val RATE=22050
    fun header(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(bytes+36); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(RATE); putInt(RATE*2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes)
    }.array()
    fun append(source: File, output: RandomAccessFile) {
        RandomAccessFile(source,"r").use { input ->
            fun ascii(n: Int)=ByteArray(n).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            fun int()=Integer.reverseBytes(input.readInt())
            fun short()=java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            require(ascii(4)=="RIFF"); int(); require(ascii(4)=="WAVE")
            var channels=0; var rate=0; var bits=0; var format=0
            var dataOffset=0L; var dataLength=0
            while(input.filePointer+8<=input.length()) {
                val name=ascii(4); val size=int()
                require(size>=0 && input.filePointer+size<=input.length()) { "TTS 音訊損毀" }
                val end=input.filePointer+size+(size%2)
                when(name) {
                    "fmt " -> { require(size>=16); format=short(); channels=short(); rate=int(); int(); short(); bits=short() }
                    "data" -> { dataOffset=input.filePointer; dataLength=size }
                }
                input.seek(end)
            }
            require(format==1 && channels in 1..2 && bits in listOf(8,16) && rate in 8000..96000 && dataOffset>0) { "此 TTS 引擎的音訊格式不支援，請更換系統 TTS 引擎" }
            val frameSize=channels*bits/8
            val frames=dataLength/frameSize
            require(frames>0 && dataLength<=32*1024*1024)
            input.seek(dataOffset)
            val samples=DoubleArray(frames)
            for(i in 0 until frames) {
                var sum=0.0
                repeat(channels) { sum+=if(bits==16) java.lang.Short.reverseBytes(input.readShort()).toDouble() else (input.readUnsignedByte()-128)*256.0 }
                samples[i]=sum/channels
            }
            val count=(frames.toLong()*RATE/rate).toInt()
            val buffer=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0 until count) {
                val position=i.toDouble()*rate/RATE
                val left=position.toInt().coerceAtMost(frames-1); val right=(left+1).coerceAtMost(frames-1)
                val value=(samples[left]+(samples[right]-samples[left])*(position-left)).toInt().coerceIn(-32768,32767)
                buffer.putShort(value.toShort())
                if(!buffer.hasRemaining()) { output.write(buffer.array()); buffer.clear() }
            }
            output.write(buffer.array(),0,buffer.position())
        }
    }
    fun silence(output: RandomAccessFile, seconds: Int) {
        val zero=ByteArray(RATE*2)
        repeat(seconds) { output.write(zero) }
    }
}
