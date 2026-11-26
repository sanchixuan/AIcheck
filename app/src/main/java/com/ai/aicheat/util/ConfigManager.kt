package com.ai.aicheat.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ai.aicheat.App
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object ConfigManager {
    private const val KEY_ALIAS = "aicheck.api.v1"

    private val store by lazy {
        ApiProfileStore(
            App.instance.getSharedPreferences("aicheat_config", Context.MODE_PRIVATE),
            ::encrypt,
            ::decrypt
        )
    }

    fun profiles() = store.profiles()
    fun save(profile: ApiProfile, newKey: String?) = store.save(profile, newKey)
    fun select(id: String) = store.select(id)
    fun delete(id: String) = store.delete(id)
    fun requestConfig() = store.requestConfig()

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey(true))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        return "$iv.$data"
    }

    private fun decrypt(sealed: String): String {
        val (iv, data) = sealed.split(".", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, deviceKey(false),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun deviceKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "设备密钥不可用" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
