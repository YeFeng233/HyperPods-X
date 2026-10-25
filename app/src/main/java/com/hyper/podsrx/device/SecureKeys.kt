package com.hyper.podsrx.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.ConcurrentHashMap

class SecureKeys(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_identity", Context.MODE_PRIVATE)
    private val cache=ConcurrentHashMap<String,ByteArray>()
    @Synchronized
    private fun storageKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hyperpods_identity", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hyperpods_identity", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(address: String, type: Int, bytes: ByteArray) {
        require(bytes.size == 16)
        if(bytes.all{it==0.toByte()}||bytes.all{it==(-1).toByte()})return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, storageKey()) }
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        preferences.edit().putString("$address:$type", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        cache["$address:$type"]=bytes.copyOf()
    }
    fun get(address: String, type: Int): ByteArray? = runCatching {
        cache["$address:$type"]?.let{return it.copyOf()}
        val data = Base64.decode(preferences.getString("$address:$type", null) ?: return null, Base64.NO_WRAP)
        if (data.size < 28) return null
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, storageKey(), GCMParameterSpec(128, data.copyOf(12)))
        }.doFinal(data.copyOfRange(12, data.size)).takeUnless{bytes->bytes.size!=16||bytes.all{it==0.toByte()}||bytes.all{it==(-1).toByte()}}
            ?.also{cache["$address:$type"]=it.copyOf()}
    }.getOrNull()
}
