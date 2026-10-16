package com.novelreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novelreader.app.data.local.NovelEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    novels: List<NovelEntity>,
    onNovelClick: (String) -> Unit,
    onReadNovel: (String, Int) -> Unit,
    onAddClick: () -> Unit,
    onOpenWebClick: () -> Unit,
    onDownloadAll: (String) -> Unit,
    onDeleteNovel: (NovelEntity) -> Unit
) {
    var novelToDelete by remember { mutableStateOf<NovelEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的書架", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenWebClick) {
                        Icon(Icons.Default.Language, contentDescription = "內建網頁瀏覽")
                    }
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = "貼上網址匯入")
                    }
                }
            )
        }
    ) { padding ->
        if (novels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("書架目前是空的", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("點選右上角 + 直接貼上網址，或用網頁圖示瀏覽匯入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddClick) {
                        Text("貼上小說網址")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(novels, key = { it.id }) { novel ->
                    NovelCardItem(
                        novel = novel,
                        onClick = { onReadNovel(novel.id, -1) }, // Use -1 to indicate resume reading latest
                        onDetailClick = { onNovelClick(novel.id) },
                        onDownload = { onDownloadAll(novel.id) },
                        onDelete = { novelToDelete = novel }
                    )
                }
            }
        }

        // 刪除確認對話框
        novelToDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { novelToDelete = null },
                title = { Text("確認刪除書籍") },
                text = { Text("確定要將《${target.title}》從書架移除嗎？這將會連同已下載的離線章節完全刪除。") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            onDeleteNovel(target)
                            novelToDelete = null
                        }
                    ) {
                        Text("確認刪除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { novelToDelete = null }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
fun NovelCardItem(
    novel: NovelEntity,
    onClick: () -> Unit,
    onDetailClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDetailClick) {
                    Icon(Icons.Default.MenuBook, contentDescription = "目錄與詳情", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "整部下載", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "刪除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "作者：${novel.author}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (novel.lastReadChapterTitle != null) "上次讀到：${novel.lastReadChapterTitle}" else "點擊開始閱讀",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "已下載 ${novel.downloadedChapters} / ${novel.totalChapters} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (novel.downloadedChapters >= novel.totalChapters && novel.totalChapters > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
