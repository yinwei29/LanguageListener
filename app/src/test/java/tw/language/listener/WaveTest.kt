package tw.language.listener

import org.junit.Test
import org.junit.Assert.*
import tw.language.listener.playback.PcmWave
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveTest {
    @Test fun concatenationPreservesPcmAndAddsRealSilence() {
        val source=File.createTempFile("source", ".wav")
        val target=File.createTempFile("output", ".wav")
        try {
            val samples=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1200).putShort(-1200).array()
            source.writeBytes(PcmWave.header(4)+samples)
            RandomAccessFile(target,"rw").use { output ->
                output.write(PcmWave.header(0)); PcmWave.append(source,output); PcmWave.silence(output,1)
            }
            assertEquals(44L+4+PcmWave.RATE*2,target.length())
            assertArrayEquals(samples,target.readBytes().copyOfRange(44,48))
            assertTrue(target.readBytes().drop(48).all { it==0.toByte() })
        } finally { source.delete(); target.delete() }
    }
}
