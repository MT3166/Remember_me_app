package com.littlediary.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProviderSettingsScreen(draft: ModelSettings, busy: String, testImage: ByteArray?, onBack: () -> Unit,
    onChange: (ModelSettings) -> Unit, onSave: () -> Unit, onTest: (ModelKind) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ModelKind.TEXT) }
    var speechTab by rememberSaveable { mutableStateOf(false) }
    val provider = if (kind == ModelKind.TEXT) draft.textProvider else draft.imageProvider
    val model = if (kind == ModelKind.TEXT) draft.textModel else draft.imageModel
    var advanced by rememberSaveable(kind, provider) { mutableStateOf(provider == Provider.CUSTOM) }
    val enabled = busy.isBlank()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "返回家长设置", onClick = onBack)
            Text("模型供应商", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = if (speechTab) 2 else kind.ordinal) {
            ModelKind.entries.forEach { item ->
                Tab(selected = !speechTab && kind == item, enabled = enabled, onClick = { speechTab = false; kind = item },
                    text = { Text(if (item == ModelKind.TEXT) "文本模型" else "图片模型") },
                    icon = { Icon(if (item == ModelKind.TEXT) Icons.Default.TextFields else Icons.Default.Image, null) })
            }
            Tab(selected = speechTab, enabled = enabled, onClick = { speechTab = true },
                text = { Text("语音识别") }, icon = { Icon(Icons.Default.Mic, null) })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (speechTab) {
                    ChoiceField("语音服务", if (draft.useMiniMaxSpeech) "MiniMax" else "系统识别", listOf("MiniMax", "系统识别"), enabled) {
                        onChange(draft.copy(useMiniMaxSpeech = it == "MiniMax"))
                    }
                    if (draft.useMiniMaxSpeech) {
                        Text("模型：asr-1.0 · 普通话", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("复用 MiniMax Key", Modifier.weight(1f))
                            Switch(draft.reuseMiniMaxKeyForSpeech, { onChange(draft.copy(reuseMiniMaxKeyForSpeech = it)) }, enabled = enabled)
                        }
                        if (draft.reuseMiniMaxKeyForSpeech) {
                            Text(if (draft.speechKeyForRequest.isNotBlank()) "已关联 MiniMax 密钥" else "尚未配置 MiniMax 密钥，请在文本或图片页填写。")
                        } else SecretField("MiniMax 语音 API Key", draft.speechKey, { onChange(draft.copy(speechKey = it)) }, enabled)
                        Text("录音将上传至 MiniMax 进行识别，按供应商用量计费。单次录音最长 8 分钟。", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                ChoiceField("供应商", provider.label, Provider.entries.map { it.label }, enabled) { label ->
                    val selected = Provider.entries.first { it.label == label }
                    onChange(if (kind == ModelKind.TEXT) draft.selectTextProvider(selected) else draft.selectImageProvider(selected))
                }
                Text("模型：$model", style = MaterialTheme.typography.titleMedium)
                if (draft.canReuseTextKey) {
                    Row(Modifier.fillMaxWidth().toggleable(value = draft.reuseTextKeyForImages, enabled = enabled, role = Role.Switch,
                        onValueChange = { onChange(draft.copy(reuseTextKeyForImages = it)) }), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (kind == ModelKind.TEXT) "与图片模型共用 Key" else "与文本模型共用 Key", Modifier.weight(1f))
                        Switch(checked = draft.reuseTextKeyForImages, enabled = enabled, onCheckedChange = null)
                    }
                }
                key(kind, provider, draft.reuseTextKeyForImages && draft.canReuseTextKey) {
                    val shared = draft.reuseTextKeyForImages && draft.canReuseTextKey
                    SecretField(if (shared) "共用 API Key" else "${provider.label} API Key",
                        if (kind == ModelKind.TEXT) draft.textKeyForRequest else draft.imageKeyForRequest,
                        onValue = { value -> onChange(if (kind == ModelKind.TEXT) draft.withTextKey(value) else draft.withImageKey(value)) }, enabled = enabled)
                }
                TextButton(onClick = { advanced = !advanced }, enabled = enabled) {
                    Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(if (advanced) "收起高级设置" else "高级设置")
                }
                if (advanced) {
                    SettingField("模型名称", model, { value -> onChange(if (kind == ModelKind.TEXT) draft.copy(textModel = value) else draft.copy(imageModel = value)) }, enabled)
                    if (provider == Provider.CUSTOM) {
                        if (kind == ModelKind.TEXT) {
                            ChoiceField("文本接口协议", if (draft.protocol == Protocol.OPENAI) "OpenAI 兼容" else "Anthropic 兼容",
                                listOf("OpenAI 兼容", "Anthropic 兼容"), enabled) {
                                onChange(draft.copy(protocol = if (it == "OpenAI 兼容") Protocol.OPENAI else Protocol.ANTHROPIC))
                            }
                            SettingField("接口基础地址", draft.textBaseUrl, { onChange(draft.copy(textBaseUrl = it)) }, enabled)
                        } else {
                            ChoiceField("图片接口协议", if (draft.imageApi == ImageApi.OPENAI) "OpenAI GPT Image" else "MiniMax 图片",
                                listOf("OpenAI GPT Image", "MiniMax 图片"), enabled) {
                                onChange(draft.copy(imageApi = if (it == "OpenAI GPT Image") ImageApi.OPENAI else ImageApi.MINIMAX))
                            }
                            SettingField("图片完整接口地址", draft.imageUrl, { onChange(draft.copy(imageUrl = it)) }, enabled)
                        }
                    }
                }
                HorizontalDivider()
                Command(if (kind == ModelKind.TEXT) "测试文本连接" else "生成测试图片",
                    if (kind == ModelKind.TEXT) Icons.Default.Wifi else Icons.Default.Palette,
                    Modifier.fillMaxWidth(), enabled = enabled, secondary = true, onClick = { onTest(kind) })
                Text(if (kind == ModelKind.TEXT) "连接测试按文本用量计费。" else "测试会生成一张图片，按供应商计费。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(busy, Modifier.padding(start = 12.dp))
                }
                if (kind == ModelKind.IMAGE && testImage != null) TestImagePreview(testImage)
                }
            }
        }
        ActionBar { Command("保存配置", Icons.Default.Save, Modifier.fillMaxWidth(), enabled = enabled, onClick = onSave) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(value, {}, Modifier.menuAnchor().fillMaxWidth(), enabled = enabled, readOnly = true,
            textStyle = MaterialTheme.typography.bodyMedium, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}

@Composable
private fun TestImagePreview(bytes: ByteArray) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    bitmap?.let { Image(it, "供应商测试图片", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
}

@Composable
fun ProviderExitDialog(busy: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("保存供应商配置？") }, text = { Text("还有未保存的配置修改。") },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text("保存并返回") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("放弃修改") }
                TextButton(onClick = onDismiss) { Text("继续编辑") }
            }
        })
}
