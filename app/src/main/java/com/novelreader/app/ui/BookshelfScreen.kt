package com.novelreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novelreader.app.data.local.NovelEntity
import com.novelreader.app.ui.components.NovelCoverPlaceholder

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
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Filter novels
    val filteredNovels = if (searchQuery.isNotBlank()) {
        novels.filter { it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) }
    } else {
        novels
    }

    // Sort: Recently read first (assuming lastReadChapterIndex >= 0 or similar is an indicator, or just latest added for now if no timestamp)
    val sortedNovels = filteredNovels.sortedByDescending { it.lastReadChapterIndex >= 0 }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜尋書架...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    actions = {
                        TextButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Text("取消")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("我的書架", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜尋")
                        }
                        IconButton(onClick = onOpenWebClick) {
                            Icon(Icons.Default.Language, contentDescription = "瀏覽網頁以匯入")
                        }
                        IconButton(onClick = onAddClick) {
                            Icon(Icons.Default.Add, contentDescription = "輸入網址新增書籍")
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            if (novels.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("書架目前是空的", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "點選右上角 + 貼上網址，或用地球圖示開啟內建瀏覽器尋找並加入小說。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("加入第一本小說")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Recent Read Section (Top 1 if any)
                    val recentNovel = sortedNovels.firstOrNull { it.lastReadChapterIndex >= 0 }
                    if (recentNovel != null && !isSearchActive) {
                        item {
                            Text("繼續閱讀", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            NovelCardItem(
                                novel = recentNovel,
                                onClick = { onReadNovel(recentNovel.id, -1) },
                                onDetailClick = { onNovelClick(recentNovel.id) },
                                onDownload = { onDownloadAll(recentNovel.id) },
                                onDelete = { novelToDelete = recentNovel },
                                isFeatured = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("全部書籍", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }

                    items(sortedNovels.filter { it.id != recentNovel?.id || isSearchActive }, key = { it.id }) { novel ->
                        NovelCardItem(
                            novel = novel,
                            onClick = { onReadNovel(novel.id, -1) },
                            onDetailClick = { onNovelClick(novel.id) },
                            onDownload = { onDownloadAll(novel.id) },
                            onDelete = { novelToDelete = novel },
                            isFeatured = false
                        )
                    }
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
    onDelete: () -> Unit,
    isFeatured: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isFeatured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (isFeatured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isFeatured) 4.dp else 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面區域
            Box(
                modifier = Modifier
                    .width(if (isFeatured) 72.dp else 64.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (novel.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = novel.coverUrl,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NovelCoverPlaceholder()
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 資訊區域
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = novel.title,
                    style = if (isFeatured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFeatured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = novel.author.ifBlank { "未知作者" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFeatured) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 閱讀進度
                val progressText = if (novel.lastReadChapterTitle != null) {
                    novel.lastReadChapterTitle
                } else {
                    "尚未開始閱讀"
                }

                Text(
                    text = progressText ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isFeatured) FontWeight.Bold else FontWeight.Normal,
                    color = if (isFeatured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${novel.downloadedChapters} / ${novel.totalChapters} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFeatured) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                )
            }

            // 選單按鈕
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "書籍選項",
                        tint = if (isFeatured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("查看詳情與目錄") },
                        leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDetailClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("背景下載全書") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onDownload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("刪除書籍", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
