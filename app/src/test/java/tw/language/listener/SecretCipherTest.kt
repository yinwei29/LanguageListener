package tw.language.listener

import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.SecretCipher
import javax.crypto.KeyGenerator

class SecretCipherTest {
    @Test fun ciphertextIsRandomizedBoundToItsSlotAndAuthenticated() {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val value="TEST_SECRET_ONLY_這不是金鑰"
        val first=SecretCipher.encrypt(key,"google-session",value)
        val second=SecretCipher.encrypt(key,"google-session",value)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("TEST_SECRET_ONLY"))
        assertEquals(value,SecretCipher.decrypt(key,"google-session",first))
        assertThrows(Exception::class.java) { SecretCipher.decrypt(key,"different-provider",first) }
        val damaged=first.copyOf(); damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { SecretCipher.decrypt(key,"google-session",damaged) }
        assertThrows(IllegalArgumentException::class.java) { SecretCipher.decrypt(key,"google-session",byteArrayOf(0)) }
    }
}
