package com.nasmusic.tv.backend.network

/**
 * 网络歌曲主键的**唯一**构造点（艺人全量方案 红线 1）。
 *
 * `MetingApiService.parseSongs` 与 `NeteaseOpenApi` 都必须经这里生成 `Song.id`。
 * 两条链路给同一首歌生成不同 id，等于同一首歌在收藏 / 下载 / 断点续传索引里变成两个实体
 * （这些主键全部建在这个字符串上）。
 */
object NetworkSongId {
    /** 形如 `ntwk_meting_2652820720`；⛔ sourceId 只能是注册源 key，不能是平台名（红线 2） */
    fun forNetworkSong(sourceId: String, netId: String): String = "ntwk_${sourceId}_$netId"
}
