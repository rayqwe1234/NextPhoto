package app.nextcloudphoto.vault

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resumeWithException

/** The saved blob is a KEK-wrapped album key, never a password or an unprotected album key. */
class VaultBiometrics(private val context: Context, account: String, id: String) {
    private val alias="photo-vault-"+MessageDigest.getInstance("SHA-256").digest("${account}/${id}".toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs=context.getSharedPreferences("vault-biometric",Context.MODE_PRIVATE)
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun enabled()=prefs.contains(alias)
    fun unavailableReason(): String {
        val manager=context.getSystemService(BiometricManager::class.java)
        val status=if(Build.VERSION.SDK_INT>=30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) else manager.canAuthenticate()
        return when(status) {
            BiometricManager.BIOMETRIC_SUCCESS -> ""
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> tr(R.string.msg_276_no_fingerprints_are_enrolled_add_a_fingerprint_in_syste)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> tr(R.string.msg_277_this_device_has_no_strong_biometrics_available_for_encr)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> tr(R.string.msg_278_biometrics_are_temporarily_unavailable_try_again_later)
            else -> tr(R.string.msg_279_strong_biometrics_are_unavailable_status_1_s_check_syst, status)
        }
    }
    fun available(): Boolean = unavailableReason().isEmpty()
    fun disable() { prefs.edit().remove(alias).commit();store().deleteEntry(alias) }
    private fun cipher(enroll: Boolean): Cipher {
        if(enroll) {
            disable()
            val spec=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
            if(Build.VERSION.SDK_INT>=30) spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
            else spec.setUserAuthenticationValidityDurationSeconds(-1)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(spec.build());generateKey() }
        }
        val key=store().getKey(alias,null) as? SecretKey ?: error(tr(R.string.msg_280_unlock_with_your_password_and_enable_fingerprint_unlock))
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if(enroll) init(Cipher.ENCRYPT_MODE,key)
            else init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,VaultCrypto.decode(prefs.getString(alias,"")!!).copyOfRange(0,12)))
        }
    }
    suspend fun authenticate(activity: Activity, header: ByteArray, enroll: VaultSession?=null): ByteArray {
        val cipher=cipher(enroll!=null)
        // A changed header cannot reuse an old biometric wrapping.
        val binding=MessageDigest.getInstance("SHA-256").digest(header)
        return suspendCancellableCoroutine { continuation ->
            val signal=CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val builder=BiometricPrompt.Builder(activity).setTitle(if(enroll!=null) tr(R.string.msg_204_enable_fingerprint_unlock) else tr(R.string.msg_281_unlock_encrypted_album))
                .setSubtitle(tr(R.string.msg_282_use_your_fingerprint_or_supported_strong_biometrics))
                .setNegativeButton(tr(R.string.msg_71_cancel),context.mainExecutor) { _,_ -> continuation.cancel() }
            if(Build.VERSION.SDK_INT>=30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            builder.build().authenticate(BiometricPrompt.CryptoObject(cipher),signal,context.mainExecutor,object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(code: Int, text: CharSequence) {
                    if(continuation.isActive) continuation.resumeWithException(IllegalStateException(tr(R.string.msg_283_1_s_you_can_use_your_album_password_instead, text)))
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if(!continuation.isActive) return
                    try {
                        val authenticated=result.cryptoObject?.cipher ?: error(tr(R.string.msg_284_key_authorization_was_not_granted))
                        authenticated.updateAAD(binding)
                        val key=if(enroll!=null) {
                            val wrapped=enroll.withKey { authenticated.doFinal(it) }
                            check(prefs.edit().putString(alias,VaultCrypto.encode(authenticated.iv+wrapped)).commit()) { tr(R.string.msg_285_cannot_save_fingerprint_settings) }
                            ByteArray(0)
                        } else {
                            val wrapped=VaultCrypto.decode(prefs.getString(alias,"")!!)
                            authenticated.doFinal(wrapped,12,wrapped.size-12)
                        }
                        continuation.resume(key,onCancellation={ _,value,_ -> value.fill(0) })
                    } catch(e: Exception) { continuation.resumeWithException(e) }
                }
            })
        }
    }
    companion object {
        fun clearAll(context: Context) {
            context.getSharedPreferences("vault-biometric",Context.MODE_PRIVATE).edit().clear().commit()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.aliases().toList().filter { it.startsWith("photo-vault-") }.forEach(keys::deleteEntry)
        }
    }
}
