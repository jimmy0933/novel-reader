package com.novelreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.novelreader.app.data.local.ChapterEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    novelId: String,
    initialChapterIndex: Int,
    repository: com.novelreader.app.data.repository.NovelRepository,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    var chapterIndex by remember { mutableStateOf(initialChapterIndex) }
    val chapters by repository.getChaptersFlow(novelId).collectAsState(initial = emptyList())
    val chapter = chapters.find { it.chapterIndex == chapterIndex }

    val hasPrevious = chapterIndex > 0
    val hasNext = chapters.isNotEmpty() && chapterIndex < chapters.size - 1

    var isLoadingChapter by remember { mutableStateOf(false) }
    var chapterError by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }

    var isFirstLaunch by remember { mutableStateOf(true) }
    var isReadyToRecord by remember { mutableStateOf(false) }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun switchChapter(newIndex: Int) {
        if (newIndex >= 0 && (chapters.isEmpty() || newIndex < chapters.size)) {
            isReadyToRecord = false
            coroutineScope.launch {
                chapterIndex = newIndex
            }
        }
    }

    val onChapterFinishedState by rememberUpdatedState {
        if (hasNext) {
            switchChapter(chapterIndex + 1)
        }
    }

    // 處理當前章節載入、即時下載、閱讀進度更新與背景預載後續 2 章
    LaunchedEffect(novelId, chapterIndex, retryTrigger) {
        val currentChapter = repository.getChapter(novelId, chapterIndex)
        if (currentChapter != null) {
            if (currentChapter.isDownloaded && !currentChapter.content.isNullOrBlank()) {
                isLoadingChapter = false
                chapterError = null
                repository.updateReadingProgress(novelId, currentChapter.chapterId, currentChapter.title, chapterIndex)
                prefetchNextChapters(repository, novelId, chapterIndex)
            } else {
                isLoadingChapter = true
                chapterError = null

                val result = repository.downloadSingleChapter(novelId, chapterIndex)
                if (result.isSuccess) {
                    isLoadingChapter = false
                    chapterError = null
                    val updated = repository.getChapter(novelId, chapterIndex)
                    if (updated != null) {
                        repository.updateReadingProgress(novelId, updated.chapterId, updated.title, chapterIndex)
                    }
                    prefetchNextChapters(repository, novelId, chapterIndex)
                } else {
                    isLoadingChapter = false
                    chapterError = result.exceptionOrNull()?.message ?: "章節下載失敗，請檢查網路連線"
                    // 下載失敗時，絕不覆蓋原有閱讀進度
                }
            }
        }
    }

    var fontSize by remember {
        mutableStateOf(prefs.getInt("font_size", 18))
    }
    var isNightMode by remember {
        mutableStateOf(prefs.getBoolean("is_night_mode", false))
    }

    // 當數值改變時儲存偏好
    fun updateFontSize(newSize: Int) {
        fontSize = newSize
        prefs.edit().putInt("font_size", newSize).apply()
    }

    fun updateNightMode(night: Boolean) {
        isNightMode = night
        prefs.edit().putBoolean("is_night_mode", night).apply()
    }

    // TTS 管理器
    var speechRate by remember {
        mutableStateOf(prefs.getFloat("tts_speech_rate", 1.0f))
    }

    // 將章節內容按行切分成段落（段落 0 為標題）
    val paragraphs = remember(chapter?.title, chapter?.content) {
        val list = mutableListOf<String>()
        if (chapter != null) {
            list.add(chapter.title)
            val raw = chapter.content ?: ""
            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                list.addAll(lines)
            } else if (chapter.isDownloaded) {
                list.add("（本章內容為空）")
            } else {
                list.add("（尚未下載本章內容，請確認連線或整部下載）")
            }
        }
        list
    }

    val ttsManager = remember {
        com.novelreader.app.tts.TtsManager(
            context = context,
            onChapterFinished = {
                onChapterFinishedState()
            },
            onParagraphStarted = { _ ->
                // 由下面的 LaunchedEffect(ttsManager.currentParagraphIndex) 自動滾動
            },
            onError = { errMsg ->
                android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        ).apply {
            updateSpeechRate(speechRate)
        }
    }

    // 當離開頁面時停止 TTS 並釋放
    DisposableEffect(Unit) {
        onDispose {
            ttsManager.release()
        }
    }

    // 當更換章節時，若正在朗讀則自動朗讀新章節各段落
    LaunchedEffect(chapter?.chapterIndex, paragraphs) {
        if (ttsManager.isSpeaking && paragraphs.isNotEmpty()) {
            ttsManager.speakParagraphs(paragraphs, 0)
        }
    }

    // 當 TTS 唸到某一行時，自動滾動到該行
    LaunchedEffect(ttsManager.currentParagraphIndex) {
        val curIdx = ttsManager.currentParagraphIndex
        if (curIdx >= 0 && curIdx < paragraphs.size) {
            lazyListState.animateScrollToItem(curIdx)
        }
    }

    val backgroundColor = if (isNightMode) Color(0xFF181818) else Color(0xFFF9F7F1) // 舒適羊皮紙/深黑夜間底色
    val barColor = if (isNightMode) Color(0xFF222222) else Color(0xFFFFFFFF)
    val contentColor = if (isNightMode) Color(0xFFE0E0E0) else Color(0xFF2C2C2C)
    val iconTint = if (isNightMode) Color(0xFFCCCCCC) else Color(0xFF444444)

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = chapter?.title ?: "閱讀器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回目錄", tint = iconTint)
                    }
                },
                actions = {
                    // 單章下載／重試按鈕（若本章未下載）
                    if (chapter != null && !chapter.isDownloaded) {
                        IconButton(
                            onClick = { retryTrigger++ }
                        ) {
                            if (isLoadingChapter) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = iconTint)
                            } else {
                                Icon(Icons.Default.Download, contentDescription = "下載本章", tint = iconTint)
                            }
                        }
                    }

                    // 系統語音設定跳轉（下載/選取真人女聲套件）
                    IconButton(onClick = { ttsManager.openTtsSettings() }) {
                        Icon(
                            Icons.Default.SettingsVoice,
                            contentDescription = "語音設定（下載女聲）",
                            tint = iconTint
                        )
                    }

                    // 語音朗讀切換
                    IconButton(onClick = {
                        if (ttsManager.isSpeaking) {
                            ttsManager.stop()
                        } else {
                            if (paragraphs.isNotEmpty()) {
                                val currentVisibleIdx = lazyListState.firstVisibleItemIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                                ttsManager.speakParagraphs(paragraphs, currentVisibleIdx)
                            } else {
                                android.widget.Toast.makeText(context, "本章尚無內容可朗讀，請先下載！", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(
                            if (ttsManager.isSpeaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "語音朗讀",
                            tint = if (ttsManager.isSpeaking) MaterialTheme.colorScheme.primary else iconTint
                        )
                    }

                    // 切換字級縮小
                    IconButton(onClick = { if (fontSize > 12) updateFontSize(fontSize - 2) }) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "縮小字體", tint = iconTint)
                    }
                    // 切換字級放大
                    IconButton(onClick = { if (fontSize < 36) updateFontSize(fontSize + 2) }) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "放大字體", tint = iconTint)
                    }
                    // 夜間/日間模式
                    IconButton(onClick = { updateNightMode(!isNightMode) }) {
                        Icon(
                            if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "切換夜間模式",
                            tint = if (isNightMode) Color(0xFFFFD54F) else iconTint
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = iconTint,
                    actionIconContentColor = iconTint
                )
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = barColor,
                contentColor = contentColor
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { if (hasPrevious) switchChapter(chapterIndex - 1) },
                        enabled = hasPrevious,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isNightMode) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary,
                            disabledContentColor = if (isNightMode) Color(0xFF555555) else Color.LightGray
                        )
                    ) {
                        Icon(Icons.Default.NavigateBefore, contentDescription = null)
                        Text("上一章")
                    }

                    // 朗讀控制區域：播放/暫停按鈕 + 4種女聲循環切換 + 語速按鈕
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (ttsManager.isSpeaking) {
                                    ttsManager.stop()
                                } else {
                                    if (paragraphs.isNotEmpty()) {
                                        val currentVisibleIdx = lazyListState.firstVisibleItemIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
                                        ttsManager.speakParagraphs(paragraphs, currentVisibleIdx)
                                    } else {
                                        android.widget.Toast.makeText(context, "本章尚無內容可朗讀，請先下載！", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                if (ttsManager.isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (ttsManager.isSpeaking) "暫停" else "朗讀",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }



                        // 語速切換按鈕 (0.8x -> 1.0x -> 1.25x -> 1.5x)
                        OutlinedButton(
                            onClick = {
                                val nextRate = when {
                                    speechRate < 0.95f -> 1.0f
                                    speechRate < 1.15f -> 1.25f
                                    speechRate < 1.45f -> 1.5f
                                    else -> 0.8f
                                }
                                speechRate = nextRate
                                prefs.edit().putFloat("tts_speech_rate", nextRate).apply()
                                ttsManager.updateSpeechRate(nextRate)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isNightMode) Color(0xFFE0E0E0) else Color(0xFF333333)
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "${speechRate}x",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    TextButton(
                        onClick = { if (hasNext) switchChapter(chapterIndex + 1) },
                        enabled = hasNext,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isNightMode) Color(0xFF90CAF9) else MaterialTheme.colorScheme.primary,
                            disabledContentColor = if (isNightMode) Color(0xFF555555) else Color.LightGray
                        )
                    ) {
                        Text("下一章")
                        Icon(Icons.Default.NavigateNext, contentDescription = null)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(backgroundColor)
        ) {
            if (isLoadingChapter) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在即時下載「${chapter?.title ?: "目前章節"}」...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                }
            } else if (chapterError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "章節下載失敗",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = chapterError ?: "未知錯誤",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { retryTrigger++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重試下載")
                        }
                        if (hasPrevious) {
                            OutlinedButton(onClick = { switchChapter(chapterIndex - 1) }) {
                                Text("返回上一章")
                            }
                        }
                    }
                }
            } else {
                // 進入或切換章節時的滾動控制：
                // 還原當前章節的歷史閱讀段落與 offset
                LaunchedEffect(chapter?.chapterIndex, paragraphs) {
                    if (paragraphs.isNotEmpty()) {
                        val savedIdx = prefs.getInt("pos_${novelId}_${chapterIndex}", 0)
                        val savedOffset = prefs.getInt("offset_${novelId}_${chapterIndex}", 0)
                        if (savedIdx in 0 until paragraphs.size) {
                            lazyListState.scrollToItem(savedIdx, savedOffset)
                        } else {
                            lazyListState.scrollToItem(0, 0)
                        }
                        isFirstLaunch = false
                        delay(50)
                        isReadyToRecord = true
                    }
                }

                // 當手動滑動或 TTS 滾動時，即時記憶當前閱讀段落
                LaunchedEffect(chapterIndex) {
                    snapshotFlow { Pair(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset) }
                        .collect { (idx, offset) ->
                            if (isReadyToRecord) {
                                prefs.edit()
                                    .putInt("pos_${novelId}_${chapterIndex}", idx)
                                    .putInt("offset_${novelId}_${chapterIndex}", offset)
                                    .apply()
                            }
                        }
                }

                // 離開頁面時再次確保保存進度
                DisposableEffect(novelId, chapterIndex) {
                    onDispose {
                        if (isReadyToRecord) {
                            val idx = lazyListState.firstVisibleItemIndex
                            val offset = lazyListState.firstVisibleItemScrollOffset
                            prefs.edit()
                                .putInt("pos_${novelId}_${chapterIndex}", idx)
                                .putInt("offset_${novelId}_${chapterIndex}", offset)
                                .apply()
                        }
                    }
                }

                val highlightBg = if (isNightMode) Color(0xFF3E2723) else Color(0xFFFFF9C4) // 高亮行背景 (夜間微暗琥珀 / 日間淡黃)
                val highlightColor = if (isNightMode) Color(0xFFFFD54F) else Color(0xFFE65100) // 高亮文字加強

                val textFontSize = fontSize.sp
                val textLineHeight = (fontSize * 1.6).sp
                val titleFontSize = (fontSize + 4).sp
                val titleLineHeight = ((fontSize + 4) * 1.4).sp

                val currentPlayingIdx = ttsManager.currentParagraphIndex

                androidx.compose.foundation.lazy.LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
                ) {
                    items(
                        count = paragraphs.size,
                        key = { idx -> idx },
                        contentType = { idx -> if (idx == 0) "title" else "paragraph" }
                    ) { idx ->
                        val isHighlighted = (currentPlayingIdx == idx)
                        val text = paragraphs[idx]

                        ParagraphItem(
                            text = text,
                            isTitle = idx == 0,
                            isHighlighted = isHighlighted,
                            fontSize = if (idx == 0) titleFontSize else textFontSize,
                            lineHeight = if (idx == 0) titleLineHeight else textLineHeight,
                            contentColor = contentColor,
                            highlightColor = highlightColor,
                            highlightBg = highlightBg
                        )
                    }

                    // 底部章節結束提示與下一章按鈕
                    item(key = "chapter_footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (hasNext) {
                                Button(
                                    onClick = { switchChapter(chapterIndex + 1) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("進入下一章")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.NavigateNext, contentDescription = null)
                                }
                            } else {
                                Text(
                                    text = "— 全書完結 / 已是最後一章 —",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun prefetchNextChapters(
    repository: com.novelreader.app.data.repository.NovelRepository,
    novelId: String,
    currentChapterIndex: Int
) {
    for (i in 1..2) {
        val targetIndex = currentChapterIndex + i
        val nextChapter = repository.getChapter(novelId, targetIndex)
        if (nextChapter != null && (!nextChapter.isDownloaded || nextChapter.content.isNullOrBlank())) {
            repository.downloadSingleChapter(novelId, targetIndex)
        }
    }
}

@Composable
private fun ParagraphItem(
    text: String,
    isTitle: Boolean,
    isHighlighted: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    contentColor: Color,
    highlightColor: Color,
    highlightBg: Color
) {
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = if (isHighlighted) highlightColor else contentColor,
        fontWeight = if (isTitle) FontWeight.Bold else if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isHighlighted) Modifier.background(highlightBg) else Modifier
            )
            .padding(
                vertical = if (isTitle) 8.dp else 6.dp,
                horizontal = 4.dp
            )
    )
}
