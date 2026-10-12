package com.nasmusic.tv.ui.screens.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.common.ActionBar
import com.nasmusic.tv.ui.components.common.LoadingIndicator
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch
import com.nasmusic.tv.backend.download.model.stateOfSong

/**
 * 统一搜索 Tab（曲库搜索）
 *
 * 搜索结果展示页：搜索输入由曲库页顶部搜索框驱动（跨源融合搜索），
 * 本 Tab 只负责展示搜索结果列表（包含来源标签）。
 * 搜索歌曲使用 UnifiedSongRow(MODE_ROW)，支持收藏/队列操作。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchTab(
    searchKeyword: String,
    searchResults: List<Song>,
    isSearching: Boolean,
    favoriteIds: Set<String>,
    queueSongIds: Set<String> = emptySet(),
    // 艺人全量方案 Q1(b)：仅艺人分支填充的"可能无版权"旁路集合（⛔ 不塞进 Song）
    unplayableIds: Set<String> = emptySet(),
    // §3.6 触底续页：hasMore 只来自接口的 more 字段，"少则不分页"天然成立
    artistPaging: com.nasmusic.tv.data.model.SongsPagingState =
        com.nasmusic.tv.data.model.SongsPagingState(),
    onLoadMoreArtistSongs: () -> Unit = {},
    // 来源点亮（搜索范围选择）
    enabledSources: Set<MusicSourceType> = emptySet(),
    onToggleSource: (MusicSourceType) -> Unit = {},
    onEnableAllSources: () -> Unit = {},
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onToggleQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onAddAllToQueue: () -> Unit = {},
    downloadStates: Map<String, DownloadState> = emptyMap(),
    onDownloadSong: (Song) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val firstItemFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // 列表回顶
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // 检测是否滚动接近底部，触发加载更多
    // ⚠️ remember 必须带 key：照抄 ui/screens/library/browse/SongList.kt:81-94 的教训
    //   （2026-09-25 审查修复 #16——无 key 时 derivedStateOf 捕获首次组合的参数快照，
    //    分页条件永远用冻结的旧列表长度/旧分页标志，续页**一次都不会触发**）。
    // key 取分页状态的**稳定字段**而非整个 SongsPagingState 对象：VM 每次发的是一个
    // copy() 出来的新实例，整个对象入 key 会在每次发值时无谓重建派生块。
    val shouldLoadMore by remember(
        searchResults.size,
        artistPaging.hasMore,
        artistPaging.isLoading,
        isSearching
    ) {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = searchResults.size
            // 提前 20 项加载下一页
            totalItems > 0 && lastVisibleIndex >= totalItems - 20 &&
                    artistPaging.hasMore && !artistPaging.isLoading && !isSearching
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMoreArtistSongs()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 来源点亮栏（决定搜索范围）
        SearchSourceBar(
            enabledSources = enabledSources,
            onToggleSource = onToggleSource,
            onEnableAll = onEnableAllSources
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            isSearching -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            searchKeyword.isNotBlank() && searchResults.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                    )
                }
            }
            searchKeyword.isNotBlank() -> {
                // 操作栏
                ActionBar(
                    songCount = searchResults.size,
                    onPlayAll = onPlayAll,
                    onAddAllToQueue = onAddAllToQueue
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 计数口径（**分源**子计数，不是结果总数）：上方 ActionBar 的数字＝整个网格的行数
                // （含 NAS/网盘/Jamendo/本地等非艺人行），播放全部/全部加入队列正是作用于此列表；
                // 本行只描述「网络音乐里该艺人」的目录命中数，两者口径不同故必须点名来源与艺人名，
                // 否则会被读成同一数量而显得自相矛盾（例：李宗盛 452 首 vs 421/421）。
                // 分页仍在飞时才显示「已加载/总数」分数；拉完后只显示已全部加载，不再显示无意义的 x/x。
                val artistLoadedCount = artistPaging.songs.size
                if (artistLoadedCount > 0) {
                    Text(
                        text = if (artistLoadedCount < artistPaging.totalCount) {
                            stringResource(
                                R.string.search_artist_source_count,
                                searchKeyword, artistLoadedCount, artistPaging.totalCount
                            )
                        } else {
                            stringResource(
                                R.string.search_artist_source_count_loaded,
                                searchKeyword, artistLoadedCount
                            )
                        },
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }

                // 歌曲列表
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = songGridColumns(),
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(searchResults.size, key = { searchResults[it].id }) { index ->
                        val song = searchResults[index]
                        UnifiedSongRow(
                            song = song,
                            mode = SongRowMode.MODE_ROW,
                            possiblyUnplayable = song.id in unplayableIds,
                            onClick = { onPlaySong(song) },
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            downloadState = downloadStates.stateOfSong(song),
                            onDownload = { onDownloadSong(song) },
                            index = index,
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                    // 续页加载态：只在"还有下一页且正在取"时占一整行
                    // （⛔ 不加 items 总数以外的 key，footer 与数据行同 id 会撞 `key` 重复）
                    if (artistPaging.hasMore && artistPaging.isLoading) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.common_loading),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
            else -> {
                // 无搜索关键词：引导
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                        Text(
                            text = stringResource(R.string.search_enter_keyword),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                    )
                }
            }
        }
    }
}

/**
 * 来源点亮栏（点亮模式）
 *
 * 决定"搜索哪些源"：点亮的源参与搜索，熄灭的源不参与。
 * 每个来源一个可切换 chip（点亮 = 亮色，熄灭 = 暗色），外加"全部点亮"按钮。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchSourceBar(
    enabledSources: Set<MusicSourceType>,
    onToggleSource: (MusicSourceType) -> Unit,
    onEnableAll: () -> Unit
) {
    // 可点亮的搜索源（与 MusicSourceType.DEFAULT_SEARCH_SOURCES 一致）
    val searchableSources = MusicSourceType.DEFAULT_SEARCH_SOURCES.toList()

    // v2.36.0 竖屏（方案 §2.4 / P0-23）：label + N 个来源 Chip + 「全部」同行且无横向滚动
    //   → 窄屏下后面的来源被推出屏幕且**滑不到**。改为「label 固定 + Chip 区可横滑」
    //   （Compose 的 scrollable 容器会把获得焦点的子项自动滚入视野，遥控器/触摸均可用）
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.search_filter_sources),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            modifier = Modifier.padding(end = 10.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
        searchableSources.forEach { source ->
            val isEnabled = source in enabledSources
            Spacer(modifier = Modifier.width(8.dp))
            FocusableSurface(
                onClick = { onToggleSource(source) },
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = if (isEnabled) NasMusicColors.Primary
                else NasMusicColors.Primary.copy(alpha = 0.2f),
                focusedContainerColor = NasMusicColors.Primary,
                contentColor = if (isEnabled) Color(0xFF0C1222)
                else NasMusicColors.TextPrimary,
                focusedContentColor = Color(0xFF0C1222)
            ) {
                Text(
                    text = (if (isEnabled) "● " else "○ ") + stringResource(source.displayNameRes),
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.small(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        FocusableSurface(
            onClick = onEnableAll,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContainerColor = NasMusicColors.Primary,
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.TextPrimary
        ) {
            Text(
                text = stringResource(R.string.search_all_sources),
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        }
    }
}
