package tw.language.listener.authoring

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretVault {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/** Keystore-wrapped AES-GCM; files live in noBackupFilesDir, outside Room/ZIP/Auto Backup. */
class DeviceVault(context: Context) : SecretVault {
    private val directory=File(context.noBackupFilesDir,"secrets").apply { mkdirs() }
    private val alias="LanguageListener.secrets.v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(name: String)=AtomicFile(File(directory,MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        .joinToString("") { "%02x".format(it) }))
    @Synchronized override fun get(name: String): String? {
        val entry=file(name)
        if(!entry.baseFile.exists()) return null
        try {
            require(entry.baseFile.length() in 30..65536)
            val bytes=entry.readFully()
            require(bytes.size in 30..65536 && bytes[0]==1.toByte())
            return SecretCipher.decrypt(key(),name,bytes)
        } catch(_: Exception) {
            entry.delete()
            throw IllegalStateException("裝置憑證已失效，請重新授權或設定 API 金鑰")
        }
    }
    @Synchronized override fun put(name: String, value: String) {
        require(value.toByteArray().size<=32000)
        val bytes=SecretCipher.encrypt(key(),name,value)
        val entry=file(name); val output=entry.startWrite()
        try { output.write(bytes); entry.finishWrite(output) }
        catch(e: Exception) { entry.failWrite(output); throw e }
    }
    @Synchronized override fun remove(name: String) { file(name).delete() }
}

internal object SecretCipher {
    fun encrypt(key: SecretKey, name: String, value: String): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key); cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size==12)
        return byteArrayOf(1)+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun decrypt(key: SecretKey, name: String, bytes: ByteArray): String {
        require(bytes.size in 30..65536 && bytes[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(13,bytes.size)).toString(Charsets.UTF_8)
    }
}
