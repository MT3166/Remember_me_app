package com.littlediary.app

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF283B30)
private val Green = Color(0xFF286C49)
private val Leaf = Color(0xFFD8EDB7)
private val Coral = Color(0xFFA74B48)
private val Paper = Color(0xFFFBFCF8)
private val SoftGreen = Color(0xFFE5EFDD)
private val Muted = Color(0xFF55674F)
private val Line = Color(0xFFD8E3D3)
private val Warm = Color(0xFFF5E9C9)
private val dateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault())
private fun date(d: Diary) = dateFormat.format(Instant.ofEpochMilli(d.createdAt))
private fun month(d: Diary) = YearMonth.from(Instant.ofEpochMilli(d.createdAt).atZone(ZoneId.systemDefault())).toString()

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Green, secondary = Coral, tertiary = Color(0xFF7B631D),
            background = Paper, surface = Color.White, onSurface = Ink, onBackground = Ink,
            primaryContainer = SoftGreen, secondaryContainer = Warm, outline = Muted, outlineVariant = Line),
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 32.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        ), content = content,
    )
}

@Composable
fun DiaryScaffold(message: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) { if (message != null) { host.showSnackbar(message, withDismissAction = true); onDismiss() } }
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(host) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick, enabled = enabled, modifier = Modifier.size(52.dp)) { Icon(icon, label, Modifier.size(26.dp)) }
    }
}

@Composable
internal fun Command(label: String, icon: ImageVector, modifier: Modifier = Modifier,
    enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = if (secondary) ButtonDefaults.buttonColors(containerColor = SoftGreen, contentColor = Green) else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun ActionBar(content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(color = Line)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun StudioInvite(onNew: () -> Unit, onRecord: () -> Unit, onLatest: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(SoftGreen, RoundedCornerShape(28.dp)).border(1.dp, Line, RoundedCornerShape(28.dp))
        .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StoryGardenIllustration()
        Text("今天，想说点什么？", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Text("想到哪里，就说到哪里。", color = Muted, textAlign = TextAlign.Center)
        Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().background(Color(0xFF19482F), RoundedCornerShape(18.dp)).padding(bottom = 5.dp)) {
            Command("按一下，开始说", Icons.Default.Mic, Modifier.fillMaxWidth().heightIn(min = 64.dp), onClick = onRecord)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onNew, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Keyboard, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("写下来")
            }
            if (onLatest != null) TextButton(onClick = onLatest, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("看一篇")
            }
        }
    }
}

@Composable
private fun StoryGardenIllustration() {
    Canvas(Modifier.size(142.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0xFFFFF4CF), size.minDimension * .42f, center)
        drawCircle(Color(0xFFFFD88A), size.minDimension * .16f, Offset(center.x - 22.dp.toPx(), center.y - 24.dp.toPx()))
        drawRoundRect(Color(0xFF8DBB86), Offset(center.x - 34.dp.toPx(), center.y + 8.dp.toPx()),
            Size(68.dp.toPx(), 32.dp.toPx()), CornerRadius(20.dp.toPx()))
        drawCircle(Color(0xFFEC9C7A), 12.dp.toPx(), Offset(center.x - 15.dp.toPx(), center.y + 6.dp.toPx()))
        drawCircle(Color(0xFF7DAA79), 13.dp.toPx(), Offset(center.x + 25.dp.toPx(), center.y + 1.dp.toPx()))
        drawRoundRect(Color(0xFF286C49), Offset(center.x - 4.dp.toPx(), center.y - 30.dp.toPx()),
            Size(8.dp.toPx(), 52.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawCircle(Color.White, 3.dp.toPx(), Offset(center.x - 16.dp.toPx(), center.y - 16.dp.toPx()))
        drawCircle(Color.White, 3.dp.toPx(), Offset(center.x + 16.dp.toPx(), center.y - 16.dp.toPx()))
    }
}

@Composable
fun HomeScreen(entries: List<Diary>, store: DiaryStore, onNew: () -> Unit, onRecord: () -> Unit,
    onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var monthOpen by remember { mutableStateOf(false) }
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = entries.filter { selectedMonth == null || month(it) == selectedMonth }
    val latest = entries.firstOrNull { it.confirmed }?.let { d -> { onOpen(d.id) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(30.dp), tint = Green); Spacer(Modifier.width(12.dp))
            Text("小小日记", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Tool(Icons.Default.Settings, "家长设置", onClick = onSettings)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 1000.dp) {
                Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { StudioInvite(onNew, onRecord, latest) }
                    DiaryCollection(visible, store, Modifier.weight(1.2f).fillMaxHeight(), selectedMonth, { monthOpen = true }, onOpen)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { StudioInvite(onNew, onRecord, latest) }
                    item { CollectionHeading(selectedMonth, Modifier.padding(horizontal = 20.dp)) { monthOpen = true } }
                    if (visible.isEmpty()) item { EmptyCollection(entries.isEmpty()) }
                    items(visible, key = { it.id }) { d -> DiaryPreview(d, store, Modifier.padding(horizontal = 20.dp)) { onOpen(d.id) } }
                }
            }
        }
        NavigationBar(containerColor = Paper, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
            NavigationBarItem(selected = true, onClick = { selectedMonth = null }, icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                label = { Text("我的日记") }, colors = NavigationBarItemDefaults.colors(indicatorColor = SoftGreen))
            NavigationBarItem(selected = false, onClick = onRecord, icon = { Icon(Icons.Default.Mic, null) }, label = { Text("说一篇") })
        }
    }
    if (monthOpen) AlertDialog(onDismissRequest = { monthOpen = false }, title = { Text("翻到哪一月？") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { selectedMonth = null; monthOpen = false }) { Text("全部日记") }
            entries.map(::month).distinct().sortedDescending().forEach { value ->
                TextButton(onClick = { selectedMonth = value; monthOpen = false }) { Text(value.replace("-", " 年 ") + " 月") }
            }
        }
    }, confirmButton = { TextButton(onClick = { monthOpen = false }) { Text("返回") } })
}

@Composable
private fun CollectionHeading(month: String?, modifier: Modifier = Modifier, onMonth: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(month?.let { it.replace("-", " 年 ") + " 月" } ?: "最近的小故事", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Tool(Icons.Default.CalendarMonth, "选择月份", onClick = onMonth)
    }
}

@Composable
private fun DiaryCollection(entries: List<Diary>, store: DiaryStore, modifier: Modifier, month: String?, onMonth: () -> Unit, onOpen: (String) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { CollectionHeading(month, onMonth = onMonth) }
        if (entries.isEmpty()) item { EmptyCollection(true) }
        items(entries, key = { it.id }) { d -> DiaryPreview(d, store) { onOpen(d.id) } }
    }
}

@Composable
private fun EmptyCollection(first: Boolean) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(42.dp), tint = Green)
        Text(if (first) "第一页，留给今天。" else "这个月还没有日记", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DiaryPreview(d: Diary, store: DiaryStore, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(d, store, Modifier.width(112.dp).aspectRatio(1f).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(date(d), color = Muted, style = MaterialTheme.typography.bodyMedium)
                if (!d.confirmed) Text(if (d.suggestedBody.isNotBlank()) "等你确认" else "草稿", color = Coral)
            }
        }
    }
}

@Composable
private fun CoverImage(diary: Diary, store: DiaryStore, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, diary.coverFile) {
        value = withContext(Dispatchers.IO) {
            store.cover(diary.coverFile)?.let { file -> runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    if (bitmap != null) Image(bitmap!!, "日记封面", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Warm), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.MenuBook, "暂无封面", Modifier.size(40.dp), tint = Green) }
}

@Composable
private fun Steps(stage: DiaryStage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        listOf("说一说", "听一听", "收起来").forEachIndexed { index, label ->
            Text("${index + 1} · $label", modifier = Modifier.background(if (stage.ordinal == index) Leaf else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, color = if (stage.ordinal == index) Ink else Muted,
                fontWeight = if (stage.ordinal == index) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun EditorScreen(diary: Diary, vm: DiaryViewModel, speech: SpeechController, speaking: Boolean,
    onBack: () -> Unit, onHome: () -> Unit, onRecord: () -> Unit, onDelete: () -> Unit,
    onSettings: () -> Unit, onRead: () -> Unit, onStopReading: () -> Unit, onAppend: () -> Unit, onMode: (InputMode) -> Unit) {
    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, if (vm.stage == DiaryStage.REVIEW) "返回记录" else "返回日记列表", onClick = onBack)
            Text(when (vm.stage) { DiaryStage.RECORD -> "今天的日记"; DiaryStage.REVIEW -> "确认这篇日记"; DiaryStage.READ -> "我的日记" },
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Box {
                Tool(Icons.Default.MoreHoriz, "更多操作", onClick = { moreOpen = true })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("家长设置") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                        onClick = { moreOpen = false; onSettings() })
                    DropdownMenuItem(text = { Text("删除日记") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = { moreOpen = false; onDelete() })
                }
            }
        }
        HorizontalDivider(color = Line)
        when (vm.stage) {
            DiaryStage.RECORD -> RecordPage(diary, vm, speech, onRecord, onHome, onMode)
            DiaryStage.REVIEW -> ReviewPage(diary, vm, speaking, onRead, onStopReading, onAppend)
            DiaryStage.READ -> ReadPage(diary, vm, speaking, onRead, onStopReading)
        }
    }
}

@Composable
private fun ColumnScope.RecordPage(d: Diary, vm: DiaryViewModel, speech: SpeechController, onRecord: () -> Unit, onHome: () -> Unit, onMode: (InputMode) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().background(if (vm.inputMode == InputMode.VOICE) Color(0xFFEEF5E5) else Paper)
        .verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Steps(DiaryStage.RECORD)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                InputMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(selected = vm.inputMode == mode, onClick = { onMode(mode) }, enabled = !speech.active,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = { Icon(if (mode == InputMode.VOICE) Icons.Default.Mic else Icons.Default.Keyboard, null, Modifier.size(20.dp)) }) {
                        Text(if (mode == InputMode.VOICE) "说一说" else "写下来")
                    }
                }
            }
            if (vm.inputMode == InputMode.TEXT) {
                OutlinedTextField(d.body, vm::editBody, Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    label = { Text("今天发生了什么？") }, minLines = 5, textStyle = MaterialTheme.typography.bodyLarge)
                Text("${d.body.length} / 12000", color = Muted, fontSize = 13.sp)
            } else {
                VoiceSurface(speech, onRecord)
                vm.recordingNotice?.let { notice ->
                    Text(notice, color = if (notice.contains("失败") || notice.contains("再试")) Coral else Muted,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                if (d.body.isNotBlank()) {
                    Text("已经记下的话", color = Muted, style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(d.body, style = MaterialTheme.typography.bodyMedium) }
                }
                if (!speech.active && speech.hasPending(d.id)) {
                    Command("重试语音识别", Icons.Default.Refresh, Modifier.fillMaxWidth(), onClick = onRecord)
                    TextButton(onClick = { speech.discard(d.id) }) { Text("丢弃录音，重新说") }
                }
            }
        }
    }
    ActionBar {
        when {
            speech.active -> {
                Command(if (speech.listening) "说完了" else "正在识别", Icons.Default.Stop, Modifier.fillMaxWidth(), enabled = speech.listening, onClick = onRecord)
                TextButton(onClick = { speech.cancel(); speech.discard(d.id) }) { Text("取消这次录音") }
            }
            vm.inputMode == InputMode.TEXT || d.body.isNotBlank() -> {
                Command(if (vm.inputMode == InputMode.TEXT) "写好了，听一听" else "说好了，听一听", Icons.AutoMirrored.Filled.VolumeUp,
                    Modifier.fillMaxWidth(), enabled = d.body.isNotBlank(), onClick = vm::correct)
                TextButton(onClick = onHome) { Text("稍后再写") }
            }
            else -> {
                Command("开始说", Icons.Default.Mic, Modifier.fillMaxWidth(), onClick = onRecord)
                TextButton(onClick = onHome) { Text("稍后再写") }
            }
        }
    }
}

@Composable
private fun VoiceSurface(speech: SpeechController, onRecord: () -> Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(speech.startedAt, speech.active) {
        seconds = 0
        while (speech.active && speech.startedAt > 0) { seconds = ((SystemClock.elapsedRealtime() - speech.startedAt) / 1000).toInt(); delay(500) }
    }
    val listening = speech.listening
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).border(1.dp, Line, RoundedCornerShape(28.dp))
        .padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("今天的声音", color = Green, style = MaterialTheme.typography.labelLarge)
        Text(if (speech.processing) "正在收好你的声音" else if (listening) "我在听，你慢慢说" else "准备好就按一下", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(if (speech.active) "想到哪里，就说到哪里。" else "开心的、难过的，都可以告诉我。", color = Muted, textAlign = TextAlign.Center)
        Canvas(Modifier.width(220.dp).height(44.dp)) {
            val gap = size.width / 17
            speech.levels.forEachIndexed { index, level ->
                val height = 6.dp.toPx() + (size.height - 6.dp.toPx()) * level
                drawRoundRect(if (listening) Green else Color(0xFFB7CDB2), Offset(index * gap, (size.height - height) / 2),
                    Size(5.dp.toPx(), height), CornerRadius(4.dp.toPx()))
            }
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(158.dp).background(if (listening) Color(0xFFFFE2D5) else Color(0xFFE2F0CE), CircleShape)) {
            Button(onClick = onRecord, enabled = !speech.processing, shape = CircleShape, modifier = Modifier.size(128.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (listening) Coral else Green), contentPadding = PaddingValues(0.dp)) {
                Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, if (listening) "结束录音" else "开始录音", Modifier.size(48.dp))
            }
        }
        Surface(color = if (listening) Color(0xFFFFF0EA) else Warm, shape = RoundedCornerShape(50.dp)) {
            Text(if (speech.active) String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60) else "按一下开始 · 再按一下结束",
                color = if (listening) Coral else Ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (speech.partial.isNotBlank()) Text(speech.partial, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ColumnScope.ReviewPage(d: Diary, vm: DiaryViewModel, speaking: Boolean, onRead: () -> Unit, onStopReading: () -> Unit, onAppend: () -> Unit) {
    var editing by rememberSaveable(d.id) { mutableStateOf(d.confirmed && d.suggestedBody.isBlank()) }
    var originalOpen by rememberSaveable(d.id) { mutableStateOf(false) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Steps(DiaryStage.REVIEW)
            Text("听听，是这样吗？", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(if (d.suggestedBody.isNotBlank()) "还是你的话，只整理了一点点。" else "先听一遍，再决定是否收好。", color = Muted,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Command(if (speaking) "停止朗读" else "听一遍", if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    enabled = d.reviewBody.isNotBlank() && vm.busy.isBlank(), secondary = true, onClick = { if (speaking) onStopReading() else onRead() })
                Spacer(Modifier.width(12.dp))
                Tool(if (editing) Icons.Default.Check else Icons.Default.Edit, if (editing) "完成修改" else "修改文字", enabled = vm.busy.isBlank()) {
                    onStopReading(); if (!editing) vm.acceptCorrection(); editing = !editing
                }
            }
            if (vm.busy.isNotBlank()) BusyRow(vm.busy, vm::cancelOperation)
            vm.correctionError?.let { error ->
                Column { Text(error, color = Coral); TextButton(onClick = vm::correct, enabled = vm.busy.isBlank()) { Text("重新整理") } }
            }
            if (editing) {
                OutlinedTextField(d.title, vm::editTitle, Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
                OutlinedTextField(d.body, vm::editBody, Modifier.fillMaxWidth().heightIn(min = 220.dp), label = { Text("日记正文") }, minLines = 5)
            } else {
                Text(d.reviewTitle, style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(d.reviewBody, Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFE6DCB8), RoundedCornerShape(8.dp)).padding(20.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if (d.questions.isNotEmpty()) { Text("再确认一下", style = MaterialTheme.typography.titleMedium, color = Coral); d.questions.forEach { Text(it, color = Coral) } }
            if (d.original.isNotBlank()) {
                TextButton(onClick = { originalOpen = !originalOpen }) {
                    Icon(if (originalOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(if (originalOpen) "收起原来的话" else "看看原来的话")
                }
                if (originalOpen) {
                    SelectionContainer { Text(d.original, color = Muted) }
                    if (d.suggestedBody.isNotBlank()) TextButton(onClick = { onStopReading(); vm.rejectCorrection() }) { Text("保留原来的话") }
                }
            }
        }
    }
    ActionBar {
        Command("就是这样，收起来", Icons.Default.Check, Modifier.fillMaxWidth(), enabled = d.reviewBody.isNotBlank() && vm.busy.isBlank()) {
            onStopReading(); vm.confirmAndCover()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            TextButton(onClick = { onStopReading(); vm.confirmAndCover(false) }, enabled = d.reviewBody.isNotBlank() && vm.busy.isBlank()) { Text("先只保存文字") }
            TextButton(onClick = onAppend, enabled = vm.busy.isBlank()) { Text("再说一段") }
        }
    }
}

@Composable
private fun BusyRow(label: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(label, Modifier.weight(1f).padding(horizontal = 14.dp))
        Tool(Icons.Default.Close, "取消等待", onClick = onCancel)
    }
}

@Composable
private fun ColumnScope.ReadPage(d: Diary, vm: DiaryViewModel, speaking: Boolean, onRead: () -> Unit, onStopReading: () -> Unit) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth >= 900.dp) {
            Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ReadingCover(d, vm) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ReadingText(d) }
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ReadingCover(d, vm); ReadingText(d)
        }
    }
    ActionBar {
        Command(if (speaking) "停止朗读" else "听我的故事", if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            Modifier.fillMaxWidth(), onClick = { if (speaking) onStopReading() else onRead() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onStopReading(); vm.editSavedDiary() }) { Icon(Icons.Default.Edit, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("改几个字") }
        }
    }
}

@Composable
private fun ReadingCover(d: Diary, vm: DiaryViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.busy == "正在画封面") {
            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp).background(SoftGreen).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CircularProgressIndicator(); Text("正在画这一天", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = vm::cancelOperation) { Text("先读日记") }
            }
        } else if (d.coverFile.isNotBlank()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CoverImage(d, vm.store, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)))
            }
        } else Row(Modifier.fillMaxWidth().background(Warm).padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(40.dp), tint = Green)
            Text("这一页，先留给文字。", style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (vm.saveFailed) Icons.Default.ErrorOutline else Icons.Default.Check, null, Modifier.size(20.dp), tint = if (vm.saveFailed) Coral else Green)
            Text(if (vm.saveFailed) "保存失败，请重试" else if (vm.saving) "正在保存" else "日记已收好", Modifier.weight(1f).padding(start = 8.dp), color = Green)
            Tool(Icons.Default.Refresh, if (d.coverFile.isBlank()) "画张封面" else "重新画封面", enabled = vm.busy.isBlank()) { vm.confirmAndCover() }
        }
        if (d.coverOutdated) Text("正文变了，可以重新画一张。", color = Coral)
        vm.coverError?.let { Text(it, color = Coral) }
    }
}

@Composable
private fun ReadingText(d: Diary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(date(d), color = Muted); Text(d.displayTitle, style = MaterialTheme.typography.headlineLarge)
        SelectionContainer { Text(d.body, style = MaterialTheme.typography.bodyLarge) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: ModelSettings, speechAvailable: Boolean, onBack: () -> Unit, onProviders: () -> Unit,
    onRetentionChange: (AudioRetention) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack); Text("家长设置", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("模型供应商", style = MaterialTheme.typography.titleLarge)
                Text("文本：${settings.textProvider.label} · ${settings.textModel}")
                Text("图片：${settings.imageProvider.label} · ${settings.imageModel}")
                Command("配置供应商", Icons.Default.Cloud, Modifier.fillMaxWidth(), onClick = onProviders)
                HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("设备与数据", style = MaterialTheme.typography.titleLarge)
                Text("语音识别：${if (settings.useMiniMaxSpeech) "MiniMax · asr-1.0" else "系统服务"}")
                RetentionField(settings.audioRetention, onRetentionChange)
                Text("日记与封面保存在本机。MiniMax 识别会上传录音并按用量计费；校准时正文发送到文字接口；生图时画面描述发送到图片接口。系统语音服务可能联网处理录音。卸载 APP 会删除本机日记。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RetentionField(value: AudioRetention, onChange: (AudioRetention) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("原始录音保留时长", style = MaterialTheme.typography.titleMedium)
        Text("只清理已经完成处理、且已归入日记的原始音频；等待识别或失败的录音会保留。", style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(value.label(), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true,
                label = { Text("保留时长") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AudioRetention.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label()) }, onClick = {
                        expanded = false
                        onChange(option)
                    })
                }
            }
        }
    }
}

@Composable
internal fun SettingField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
}

@Composable
internal fun SecretField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = {
            Tool(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥", onClick = { visible = !visible })
        })
}

@Composable
fun ParentGate(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val left = remember { (12..19).random() }; val right = remember { (6..9).random() }
    var answer by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("请家长来设置") }, text = {
        OutlinedTextField(answer, { answer = it }, label = { Text("$left + $right = ?") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = answer.toIntOrNull() == left + right) { Text("进入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}

@Composable
fun ConfirmDelete(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除这篇日记？") }, text = { Text("正文和封面都会删除，无法恢复。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("留下") } })
}

@Composable
fun LoadingScreen() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

@Composable
fun LoadFailureScreen(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("日记暂时无法打开", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(20.dp))
        Command("重新读取", Icons.Default.Refresh, onClick = onRetry)
    }
}
