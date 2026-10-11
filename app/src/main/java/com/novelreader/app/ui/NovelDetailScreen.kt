package com.novelreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelreader.app.data.local.ChapterEntity
import com.novelreader.app.data.local.NovelEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novel: NovelEntity?,
    chapters: List<ChapterEntity>,
    onBack: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onDownloadAll: () -> Unit,
    onSyncLatestChapters: () -> Unit
) {
    var isSyncing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = novel?.title ?: "作品詳情",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isSyncing) {
                                isSyncing = true
                                onSyncLatestChapters()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "同步最新章節")
                    }
                    IconButton(onClick = onDownloadAll) {
                        Icon(Icons.Default.Download, contentDescription = "下載整部小說")
                    }
                }
            )
        }
    ) { padding ->
        if (novel == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 小說簡介區域
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = novel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "作者：${novel.author}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (novel.intro.isNotBlank()) {
                            Text(
                                text = novel.intro,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // 繼續閱讀按鈕（直接進到之前看的章節）
                        val lastIndex = novel.lastReadChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                        val readBtnText = if (!novel.lastReadChapterTitle.isNullOrBlank()) {
                            "繼續閱讀：${novel.lastReadChapterTitle}"
                        } else {
                            "開始閱讀第一章"
                        }

                        Button(
                            onClick = { onChapterClick(lastIndex) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(readBtnText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDownloadAll,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("下載 (${novel.downloadedChapters}/${novel.totalChapters})")
                            }

                            OutlinedButton(
                                onClick = onSyncLatestChapters,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("同步更新")
                            }
                        }
                    }
                }

                Text(
                    text = "章節目錄 (共 ${chapters.size} 章)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // 章節列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(chapters, key = { it.chapterIndex }) { chapter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChapterClick(chapter.chapterIndex) }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (chapter.isDownloaded) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "已離線",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
