package com.ai.aicheat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.aicheat.util.ApiProfile
import com.ai.aicheat.util.ApiProfileSelection
import com.ai.aicheat.util.ConfigManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun ApiProfilesCard() {
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<ApiProfileSelection?>(null) }
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var deleting by remember { mutableStateOf<ApiProfile?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun update(onSuccess: () -> Unit = {}, operation: () -> ApiProfileSelection) {
        try {
            selection = withContext(Dispatchers.IO) { operation() }
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "请检查配置"
        } catch (e: Exception) {
            error = "无法读取或保存 API 配置，请重试"
        } finally {
            busy = false
        }
    }

    fun runUpdate(onSuccess: () -> Unit = {}, operation: () -> ApiProfileSelection) {
        if (busy) return
        busy = true
        error = null
        scope.launch { update(onSuccess, operation) }
    }

    LaunchedEffect(Unit) { update(operation = ConfigManager::profiles) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API 配置", style = MaterialTheme.typography.titleMedium)
            val current = selection
            when {
                current == null && busy -> Text("正在读取配置…")
                current == null -> TextButton(
                    onClick = { runUpdate(operation = ConfigManager::profiles) }
                ) { Text("重新读取") }
                else -> {
                    if (current.activeId == null) Text("请添加并选择要使用的 API")
                    for (profile in current.profiles) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = profile.id == current.activeId,
                                onClick = { runUpdate { ConfigManager.select(profile.id) } },
                                enabled = !busy
                            )
                            Column(Modifier.weight(1f)) {
                                Text(profile.name)
                                if (profile.id == current.activeId) {
                                    Text("使用中", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextButton(onClick = {
                                error = null
                                editing = profile
                            }, enabled = !busy) { Text("编辑") }
                        }
                    }
                    Button(onClick = {
                        error = null
                        editing = ApiProfile(id = UUID.randomUUID().toString())
                    }, enabled = !busy) { Text("添加 API") }
                }
            }
            if (editing == null && deleting == null) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    editing?.let { profile ->
        val existing = selection?.profiles?.any { it.id == profile.id } == true
        ApiProfileDialog(
            profile = profile,
            existing = existing,
            busy = busy,
            error = error,
            onDismiss = {
                editing = null
                error = null
            },
            onDelete = {
                editing = null
                error = null
                deleting = profile
            },
            onSave = { draft, newKey ->
                runUpdate(onSuccess = { editing = null }) { ConfigManager.save(draft, newKey) }
            }
        )
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("删除 API 配置？") },
            text = {
                val active = if (profile.id == selection?.activeId) "删除后需重新选择 API。" else ""
                Text(error ?: "将删除「${profile.name}」和它保存的密钥。$active")
            },
            confirmButton = {
                TextButton(onClick = {
                    runUpdate(onSuccess = { deleting = null }) { ConfigManager.delete(profile.id) }
                }, enabled = !busy) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleting = null
                    error = null
                }, enabled = !busy) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ApiProfileDialog(
    profile: ApiProfile,
    existing: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (ApiProfile, String?) -> Unit
) {
    var draft by remember(profile) { mutableStateOf(profile) }
    var key by remember(profile) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing) "编辑 API 配置" else "添加 API 配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name, onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("配置名称") }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.url, onValueChange = { draft = draft.copy(url = it) },
                    label = { Text("API 地址") },
                    supportingText = { Text("填写支持图片输入的 Chat Completions 完整 HTTPS 地址") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("API Key") },
                    supportingText = if (existing) {{ Text("留空保留原密钥") }} else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.model, onValueChange = { draft = draft.copy(model = it) },
                    label = { Text("模型") }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.prompt, onValueChange = { draft = draft.copy(prompt = it) },
                    label = { Text("提示词") }, enabled = !busy, minLines = 2, maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft, key.takeIf { it.isNotBlank() }) }, enabled = !busy) {
                Text("保存")
            }
        },
        dismissButton = {
            if (existing) TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
