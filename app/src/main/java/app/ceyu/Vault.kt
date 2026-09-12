package app.ceyu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Vault(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "vault.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ceyu.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ceyu.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 28) { "本地档案已损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
        private fun derive(password: String, salt: ByteArray): SecretKey {
            require(password.length >= 10) { "备份口令至少 10 个字符" }
            val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
            return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
            finally { spec.clearPassword() }
        }
        fun export(data: JSONObject, password: String): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt))
            return JSONObject().put("format", "ceyu-backup-1").put("salt", b64(salt)).put("iv", b64(cipher.iv))
                .put("data", b64(cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8)))).toString()
        }
        fun import(text: String, password: String): JSONObject {
            require(text.length <= 24 * 1024 * 1024) { "备份文件过大" }
            val obj = JSONObject(text)
            require(obj.getString("format") == "ceyu-backup-1") { "不是侧语备份" }
            val salt = decode(obj.getString("salt"))
            val iv = decode(obj.getString("iv"))
            require(salt.size == 16 && iv.size == 12) { "备份参数无效" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
            val result = JSONObject(String(cipher.doFinal(decode(obj.getString("data"))), Charsets.UTF_8))
            Analysis.validateArchive(result)
            return result
        }
    }
}
