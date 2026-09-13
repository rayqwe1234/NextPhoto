package app.nextcloudphoto.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(val server: String, val user: String, val password: String, val login: String = user) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest("${server}/${user}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
interface CredentialStore {
    fun save(account: Account)
    fun read(): Account?
    fun clear()
}
class AccountStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "nextcloud-photo-account"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun save(account: Account) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val text = JSONObject().put("server", account.server).put("user", account.user).put("login",account.login).put("password", account.password).toString()
        check(prefs.edit().putString("value", Base64.encodeToString(cipher.doFinal(text.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    override fun read(): Account? {
        val value = prefs.getString("value", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            }
            val json = JSONObject(String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP))))
            Account(json.getString("server"), json.getString("user"), json.getString("password"),json.optString("login",json.getString("user")))
        }.getOrNull()
    }
    override fun clear() { prefs.edit().clear().commit() }
}
