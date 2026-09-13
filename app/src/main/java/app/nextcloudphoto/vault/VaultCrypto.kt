package app.nextcloudphoto.vault

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Versioned authenticated envelopes. AAD binds every object to its album, ID and role. */
object VaultCrypto {
    const val ITERATIONS = 600_000
    private val random = SecureRandom()
    fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == 16)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); password.fill('\u0000') }
    }
    fun seal(key: ByteArray, plain: ByteArray, context: String): ByteArray {
        val nonce = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,nonce))
        cipher.updateAAD("NextcloudPhoto/vault/v1/${context}".toByteArray())
        return nonce + cipher.doFinal(plain)
    }
    fun open(key: ByteArray, sealed: ByteArray, context: String): ByteArray {
        require(sealed.size >= 28) { tr(R.string.msg_286_encrypted_file_is_incomplete) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), GCMParameterSpec(128,sealed.copyOfRange(0,12)))
        cipher.updateAAD("NextcloudPhoto/vault/v1/${context}".toByteArray())
        return cipher.doFinal(sealed,12,sealed.size-12)
    }
    fun header(id: String, name: String, password: CharArray, key: ByteArray): ByteArray {
        require(password.size in 12..256) { tr(R.string.msg_287_use_an_album_password_with_12_to_256_characters) }
        require(name.isNotBlank() && name.length <= 100) { tr(R.string.msg_288_album_names_must_contain_1_to_100_characters) }
        val salt=random(16)
        val derived=derive(password,salt)
        val title=name.toByteArray()
        return try {
            JSONObject().put("version",1).put("id",id).put("iterations",ITERATIONS)
                .put("salt",encode(salt)).put("wrappedKey",encode(seal(derived,key,"${id}/key")))
                .put("title",encode(seal(key,title,"${id}/title"))).toString().toByteArray()
        } finally { derived.fill(0);title.fill(0) }
    }
    fun parse(header: ByteArray, id: String): JSONObject = JSONObject(header.toString(Charsets.UTF_8)).also {
        require(it.getInt("version")==1 && it.getInt("iterations")==ITERATIONS && it.getString("id")==id) { tr(R.string.msg_289_unsupported_encrypted_album_format) }
    }
    fun unlock(header: ByteArray, id: String, password: CharArray): ByteArray {
        try {
            val json=parse(header,id)
            val derived=derive(password,decode(json.getString("salt")))
            return try { open(derived,decode(json.getString("wrappedKey")),"${id}/key").also { require(it.size==32) } }
            finally { derived.fill(0) }
        } finally { password.fill('\u0000') }
    }
}
