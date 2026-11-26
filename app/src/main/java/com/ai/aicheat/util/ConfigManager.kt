package com.ai.aicheat.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.ai.aicheat.App

/**
 * 配置存储工具
 * 用于持久化保存API配置等信息
 */
object ConfigManager {
    
    private const val PREF_NAME = "aicheat_config"
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_PROMPT = "prompt"
    
    private val prefs: SharedPreferences by lazy {
        App.instance.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    val apiUrl: String
        get() = prefs.getString(KEY_API_URL, "https://api.openai.com/v1/chat/completions") ?: ""
    
    private const val SEALED_KEY = "api_key_gcm_v1"
    private const val KEY_ALIAS = "aicheck.api.v1"

    @Synchronized
    fun readApiKey(): String {
        val sealed = prefs.getString(SEALED_KEY, null)
        if (sealed != null) {
            val (iv, data) = sealed.split(".", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deviceKey(false),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            return cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
        val legacy = prefs.getString(KEY_API_KEY, null) ?: return ""
        check(withKey(prefs.edit(), legacy).commit()) { "无法迁移密钥" }
        return legacy
    }

    data class RequestConfig(val url: String, val key: String, val model: String, val prompt: String) {
        override fun toString() = "RequestConfig(redacted)"
    }

    @Synchronized
    fun requestConfig() = RequestConfig(apiUrl, readApiKey(), model, prompt)

    @Synchronized
    fun save(url: String, key: String, model: String, prompt: String) {
        val edit = prefs.edit().putString(KEY_API_URL, url)
            .putString(KEY_MODEL, model).putString(KEY_PROMPT, prompt)
        check(withKey(edit, key).commit()) { "无法保存配置" }
    }

    private fun withKey(edit: SharedPreferences.Editor, value: String): SharedPreferences.Editor {
        // Commit the encrypted value and removal of the legacy value together.
        edit.remove(KEY_API_KEY)
        if (value.isEmpty()) {
            edit.remove(SEALED_KEY)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deviceKey(true))
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val data = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            edit.putString(SEALED_KEY, "$iv.$data")
        }
        return edit
    }

    private fun deviceKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "设备密钥不可用" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    val model: String
        get() = prefs.getString(KEY_MODEL, "gpt-4o") ?: ""
    
    val prompt: String
        get() = prefs.getString(KEY_PROMPT, "请仔细分析这张图片，如果是题目请给出答案，如果是其他内容请简要描述。回答要简洁。") ?: ""
    
}
