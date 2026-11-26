package com.ai.aicheat.util

import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class ApiProfile(
    val id: String,
    val name: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
    val prompt: String = DEFAULT_PROMPT
) {
    // A custom URL may carry query parameters. Keep configuration values out of logs.
    override fun toString() = "ApiProfile(redacted)"

    companion object {
        const val DEFAULT_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o"
        const val DEFAULT_PROMPT = "请仔细分析这张图片，如果是题目请给出答案，如果是其他内容请简要描述。回答要简洁。"
    }
}

internal data class ApiProfileSelection(val profiles: List<ApiProfile>, val activeId: String?)

internal data class RequestConfig(val url: String, val key: String, val model: String, val prompt: String) {
    override fun toString() = "RequestConfig(redacted)"
}

internal class ApiProfileStore(
    private val prefs: SharedPreferences,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String
) {
    private data class Entry(val profile: ApiProfile, val sealedKey: String?)
    private data class Snapshot(val entries: List<Entry>, val activeId: String?) {
        fun selection() = ApiProfileSelection(entries.map { it.profile }, activeId)
    }

    @Synchronized
    fun profiles() = load().selection()

    @Synchronized
    fun save(profile: ApiProfile, newKey: String?): ApiProfileSelection {
        val key = newKey?.trim()?.takeIf { it.isNotEmpty() }
        val cleaned = profile.copy(
            name = profile.name.trim(), url = profile.url.trim(), model = profile.model.trim()
        )
        require(cleaned.name.isNotEmpty()) { "请输入配置名称" }
        val url = cleaned.url.toHttpUrlOrNull()
        require(url?.isHttps == true && url.username.isEmpty() && url.password.isEmpty()) {
            "请输入有效的 HTTPS API 地址"
        }
        require(cleaned.model.isNotEmpty()) { "请输入模型名称" }
        val current = load()
        require(current.entries.none {
            it.profile.id != cleaned.id && it.profile.name.equals(cleaned.name, ignoreCase = true)
        }) { "配置名称已存在" }
        val previous = current.entries.find { it.profile.id == cleaned.id }
        require(key != null || previous?.sealedKey != null) { "请输入 API Key" }
        if (key == null && previous != null && previous.profile.url != cleaned.url) {
            val oldUrl = previous.profile.url.toHttpUrlOrNull()
            require(oldUrl != null && oldUrl.scheme == url.scheme && oldUrl.host == url.host &&
                oldUrl.port == url.port) { "更换 API 服务地址时，请重新填写 API Key" }
        }
        val entry = Entry(cleaned, key?.let(encrypt) ?: previous?.sealedKey)
        val entries = if (previous == null) current.entries + entry else current.entries.map {
            if (it.profile.id == cleaned.id) entry else it
        }
        return write(Snapshot(entries, if (current.entries.isEmpty()) cleaned.id else current.activeId))
    }

    @Synchronized
    fun select(id: String): ApiProfileSelection {
        val current = load()
        require(current.entries.any { it.profile.id == id }) { "配置已不存在" }
        return write(current.copy(activeId = id))
    }

    @Synchronized
    fun delete(id: String): ApiProfileSelection {
        val current = load()
        // Require a deliberate choice before sending data to a different endpoint.
        val active = current.activeId?.takeUnless { it == id }
        return write(Snapshot(current.entries.filterNot { it.profile.id == id }, active))
    }

    @Synchronized
    fun requestConfig(): RequestConfig? {
        val current = load()
        val entry = current.entries.find { it.profile.id == current.activeId } ?: return null
        val profile = entry.profile
        val key = entry.sealedKey?.let(decrypt).orEmpty()
        return RequestConfig(profile.url, key, profile.model, profile.prompt)
    }

    private fun load(): Snapshot {
        val saved = prefs.getString("api_profiles_v1", null) ?: return migrateLegacy()
        val root = JSONObject(saved)
        val profiles = root.getJSONArray("profiles")
        val entries = List(profiles.length()) { index ->
            val json = profiles.getJSONObject(index)
            val profile = ApiProfile(
                json.getString("id"), json.getString("name"), json.getString("url"),
                json.getString("model"), json.getString("prompt")
            )
            Entry(profile, if (json.isNull("key")) null else json.getString("key"))
        }
        return Snapshot(entries, if (root.isNull("activeId")) null else root.getString("activeId"))
    }

    private fun migrateLegacy(): Snapshot {
        val legacyFields = listOf("api_url", "api_key", "api_key_gcm_v1", "model", "prompt")
        if (legacyFields.none(prefs::contains)) return Snapshot(emptyList(), null)

        val profile = ApiProfile(
            id = "legacy", name = "原有配置",
            url = prefs.getString("api_url", ApiProfile.DEFAULT_URL) ?: ApiProfile.DEFAULT_URL,
            model = prefs.getString("model", ApiProfile.DEFAULT_MODEL) ?: ApiProfile.DEFAULT_MODEL,
            prompt = prefs.getString("prompt", ApiProfile.DEFAULT_PROMPT) ?: ApiProfile.DEFAULT_PROMPT
        )
        val sealed = prefs.getString("api_key_gcm_v1", null)
            ?: prefs.getString("api_key", null)?.takeIf { it.isNotEmpty() }?.let(encrypt)
        val snapshot = Snapshot(listOf(Entry(profile, sealed)), profile.id)
        val edit = prefs.edit()
        legacyFields.forEach { edit.remove(it) }
        write(snapshot, edit)
        return snapshot
    }

    private fun write(snapshot: Snapshot, edit: SharedPreferences.Editor = prefs.edit()): ApiProfileSelection {
        val profiles = JSONArray()
        for ((profile, sealed) in snapshot.entries) {
            profiles.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("url", profile.url).put("model", profile.model).put("prompt", profile.prompt)
                .put("key", sealed ?: JSONObject.NULL))
        }
        val json = JSONObject().put("profiles", profiles).put("activeId", snapshot.activeId ?: JSONObject.NULL)
        check(edit.putString("api_profiles_v1", json.toString()).commit()) { "无法写入配置" }
        return snapshot.selection()
    }
}
