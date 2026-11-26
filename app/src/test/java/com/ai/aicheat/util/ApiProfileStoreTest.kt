package com.ai.aicheat.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

class ApiProfileStoreTest {
    private val records = mutableMapOf<String, String>()
    private val prefs = interfaceProxy<SharedPreferences> { _, method, args ->
        when (method) {
            "contains" -> records.containsKey(args[0] as String)
            "getString" -> records[args[0] as String] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected SharedPreferences method $method")
        }
    }

    private fun store() = ApiProfileStore(prefs, { "sealed:${it.reversed()}" }, {
        check(it.startsWith("sealed:"))
        it.removePrefix("sealed:").reversed()
    })

    @Test
    fun migratesLegacySecretAndPreservesSettings() {
        for ((slot, stored) in listOf("api_key" to "old-secret", "api_key_gcm_v1" to "sealed:terces-dlo")) {
            records.clear()
            records.putAll(mapOf(slot to stored, "model" to "vision", "prompt" to "中文"))
            assertEquals("原有配置", store().profiles().profiles.single().name)
            assertEquals(RequestConfig(ApiProfile.DEFAULT_URL, "old-secret", "vision", "中文"), store().requestConfig())
            assertFalse(records.containsKey(slot))
            assertFalse(records.getValue("api_profiles_v1").contains("old-secret"))
        }
    }

    @Test
    fun onlySelectedProfileSuppliesRequestConfiguration() {
        val first = ApiProfile("one", "First")
        val second = ApiProfile("two", "Second", "https://other.example/v1/chat/completions", "vision-2", "提示")
        store().save(first, "one-key")
        store().save(second, "two-key")
        assertEquals("one-key", store().requestConfig()?.key)
        store().select(second.id)
        val expected = RequestConfig(second.url, "two-key", second.model, second.prompt)
        assertEquals(expected, store().requestConfig())
        store().save(second.copy(name = "Renamed"), null)
        assertEquals(expected, store().requestConfig())
        store().delete(second.id)
        assertNull(store().requestConfig())
        assertEquals(listOf(first), store().profiles().profiles)
    }

    @Test
    fun preventsReusingAStoredKeyAtADifferentOrigin() {
        val profile = ApiProfile("one", "First")
        store().save(profile, "one-key")
        val updated = profile.copy(url = "https://other.example/v1/chat/completions")
        assertThrows(IllegalArgumentException::class.java) { store().save(updated, null) }
        assertEquals(profile.url, store().requestConfig()?.url)
        store().save(updated, "new-key")
        assertEquals("new-key", store().requestConfig()?.key)
    }

    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, String?>()
        return interfaceProxy { proxy, method, args ->
            when (method) {
                "putString" -> pending[args[0] as String] = args[1] as String?
                "remove" -> pending[args[0] as String] = null
                "commit" -> {
                    for ((key, value) in pending) {
                        if (value == null) records.remove(key) else records[key] = value
                    }
                    return@interfaceProxy true
                }
                else -> error("Unexpected Editor method $method")
            }
            proxy
        }
    }

    private inline fun <reified T : Any> interfaceProxy(crossinline call: (Any, String, Array<out Any?>) -> Any?): T =
        checkNotNull(T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
            proxy, method, args -> call(proxy, method.name, args ?: emptyArray())
        }))
}
