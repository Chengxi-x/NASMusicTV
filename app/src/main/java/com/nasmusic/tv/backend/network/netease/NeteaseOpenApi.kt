package com.nasmusic.tv.backend.network.netease

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.network.ArtistSongsPage
import com.nasmusic.tv.backend.network.NetworkArtistInfo
import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐**免鉴权原生接口**客户端（艺人全量方案 §3.1）。
 *
 * 与 Meting 端点无关：直接打 `music.163.com`，因此
 * - 不受公共端点 429 限流影响（§2 假信号 2）
 * - 但随时可能下线（R1）→ 所有调用点都必须能接受"失败即回落 Meting"
 *
 * ⚠️ 解析全部走 `internal` 纯函数，单测用 fixture 覆盖，不打网络。
 */
class NeteaseOpenApi(
    /** 基址复用歌词设置项（默认 https://music.163.com），用户改歌词基址时本功能一起跟随 */
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "NeteaseOpenApi"
        private const val DIAG = "MetingDiag"
        /** ⛔ 网易侧实测：limit>100 时 total 会随 limit 抖动（§2 D 组 241/194/1070），必须锁 100 */
        private const val MAX_LIMIT = 100
        private const val CONNECT_READ_TIMEOUT_MS = 2_500L   // §10.3 子预算（实测 p95 0.368s）

        /** 与 MetingApiService.sourceId 一致（红线 2）；⛔ 绝不能写 "netease" */
        private const val METING_SOURCE = "meting"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        val EMPTY_PAGE = ArtistSongsPage(emptyList(), 0, false, emptySet())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(CONNECT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CONNECT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** 入参即钳制，不依赖调用方自觉（§3.1） */
        internal fun clampLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)
    }

    private val gson = Gson()

    private val baseUrl: String
        get() = baseUrlProvider().trim().trimEnd('/')

    /** `POST /api/search/get` `type=100`：仅当网络段来自 Meting 端点（拿不到 `Song.artistId`）时兜底调用一次 */
    suspend fun findArtist(name: String): NetworkArtistInfo? {
        val artists = postSearchGet(type = "100", s = name, offset = 0, limit = MAX_LIMIT)
            ?.let { parseArtists(it) } ?: return null
        return artists.firstOrNull()
    }

    /** `POST /api/search/get` `type=1`：Q2 的"网易优先"搜索分支 */
    suspend fun searchSongs(keyword: String, offset: Int = 0, limit: Int = MAX_LIMIT): List<Song> {
        val body = postSearchGet(type = "1", s = keyword, offset = offset, limit = clampLimit(limit))
            ?: return emptyList()
        return parseType1Songs(body)
    }

    /** `GET /api/v1/artist/songs`：艺人全量分页（§2 D 组：566 首 / more 末页转 false） */
    suspend fun artistSongs(artistId: Long, offset: Int, limit: Int = MAX_LIMIT): ArtistSongsPage {
        val url = "${baseUrl}/api/v1/artist/songs?id=$artistId" +
            "&offset=$offset&limit=${clampLimit(limit)}&total=true&order=hot"
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = execute(request) ?: return ArtistSongsPage(emptyList(), 0, hasMore = false, emptySet())
        return parseArtistSongs(body)
    }

    private suspend fun postSearchGet(type: String, s: String, offset: Int, limit: Int): String? {
        val form = FormBody.Builder()
            .add("s", s)
            .add("type", type)
            .add("offset", offset.toString())
            .add("total", "true")
            .add("limit", clampLimit(limit).toString())
            .build()
        val request = Request.Builder()
            .url("${baseUrl}/api/search/get")
            .post(form)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(DIAG, "netease http=${resp.code} url=${request.url}")
                    return@use null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            // R1：接口下线/超时是**预期内**的常态，调用方按"回落 Meting"处理，不当异常上抛
            AppLog.w(DIAG, "netease request failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- 纯解析（internal，单测直打） ----

    /**
     * id 归一（红线 1 的守卫）：网易会把一部分 id 发成 **JSON 字符串**。
     *
     * `JsonPrimitive.toString()` 对字符串形态**连引号一起返回**（`"1001"` → `"\"1001\""`），
     * 一旦进 `NetworkSongId` 就破坏了 `ntwk_meting_<netId>` 的逐字符格式，
     * 跨源 id 相等与 Task 4 的艺人 id 强匹配会静默失效。
     *
     * - 字符串形态 → `asString`（不带引号）
     * - 数字形态 → 保持既有行为逐字节不变（`1001` 与 `1001.0` 均得 `"1001"`）
     * - 缺失 / JSON null → null（不猜）
     */
    private fun JsonElement?.idString(): String? {
        val element = this ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) return element.asString
        return element.toString().removeSuffix(".0")
    }

    /** 数字形态的 `privilege.st`；缺失 / JSON null / 非数字 / 超出 Int 范围一律回 null（不猜）。 */
    private fun JsonElement?.stIntOrNull(): Int? {
        val element = this ?: return null
        if (!element.isJsonPrimitive) return null
        if (!element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asInt }.getOrNull()
    }

    /**
     * 统一 `Song` 构造器（§3.2）。**两条分支共用**，以保证同一首歌在两处 id 逐字符一致（红线 1）。
     *
     * 字段缺失即 null / 默认值——这正是 §2 C.1 的 0/100 需要的形状：`type=1` 无 `privilege`、
     * 无 `album.picUrl`，此处不猜测、不用别的端点补。
     */
    internal fun toSong(s: JsonObject, artistIdOverride: String? = null): Song? {
        val netId = s.get("id").idString() ?: return null
        val title = s.get("name")?.asString ?: return null
        val artists = s.get("artists")?.asJsonArray
        val artist = artists?.mapNotNull { it.asJsonObject.get("name")?.asString }
            ?.joinToString(" / ").orEmpty()
        val firstArtist = artists?.firstOrNull()?.asJsonObject
        val albumObj = s.get("album")?.asJsonObject
        return Song(
            id = NetworkSongId.forNetworkSong(METING_SOURCE, netId),   // 红线 1 + 红线 2
            title = title,
            artist = artist,
            artistId = artistIdOverride ?: firstArtist?.get("id").idString(),
            album = albumObj?.get("name")?.asString.orEmpty(),
            albumId = albumObj?.get("id").idString(),
            coverUrl = albumObj?.get("picUrl")?.asString,              // type=1 → null（§2 C.1）
            durationMs = s.get("duration")?.asLong ?: 0L,              // 网易已是毫秒
            isNetworkSong = true,
            networkSource = METING_SOURCE,                             // ⛔ 不是 "netease"
            networkId = netId
        )
    }

    internal fun parseArtistSongs(body: String): ArtistSongsPage {
        val data = runCatching { gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("data") }
            .getOrElse { AppLog.e(TAG, "parseArtistSongs failed", it); return EMPTY_PAGE }
        val items = data?.getAsJsonArray("songs") ?: return EMPTY_PAGE
        val songs = items.mapNotNull { runCatching { toSong(it.asJsonObject) }.getOrNull() }
        // ⛔ hasMore 只认接口的 more：§2 D 实测末页恰满时 more 已转 false，
        //   照抄 MainViewModel.kt:1143 的 size==pageSize 会多发一次空请求
        val hasMore = data.get("more")?.asBoolean ?: false
        val total = data.get("total")?.asInt ?: songs.size
        return ArtistSongsPage(songs, total, hasMore, unplayableIdsOf(items))
    }

    internal fun parseType1Songs(body: String): List<Song> {
        val result = runCatching { gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("result") }
            .getOrNull() ?: return emptyList()
        val items = result.getAsJsonArray("songs") ?: return emptyList()
        // type=1 无 privilege（§2 C.1 0/100）→ 这里**不**生成打标集；
        // 但 artists[].id 会经 toSong 落进 Song.artistId（§2 C：distinct_artist_ids=54），
        // Task 4 的强命中判定因此零额外请求。
        return items.mapNotNull { runCatching { toSong(it.asJsonObject) }.getOrNull() }
    }

    internal fun parseArtists(body: String): List<NetworkArtistInfo> {
        val arr = runCatching {
            gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("result")?.getAsJsonArray("artists")
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { a ->
            val o = a.asJsonObject
            val id = o.get("id")?.asLong ?: return@mapNotNull null
            NetworkArtistInfo(
                id = id.toString(),
                name = o.get("name")?.asString ?: return@mapNotNull null,
                songCount = o.get("musicSize")?.asInt ?: 0
            )
        }
    }

    /**
     * `privilege.st != 0` 的曲目 id 集合。
     *
     * ⚠️ **只用于打标/置顶，⛔ 不可用于过滤**（T2：`st` 已被降级为"仅作分布参考"，
     * 而 `type=1` 形态根本没有这个字段 → 缺字段时返回空集，绝不猜）。
     *
     * **对上游 total（绝不抛）**：本函数在 `parseArtistSongs` 的 `runCatching` 之外被调用，
     * 而畸形记录很容易踩到抛点（`{"privilege":null}` → `getAsJsonObject` 强转 ClassCastException、
     * `{"st":null}` → `JsonNull.getAsInt()` 抛 UnsupportedOperationException、
     * 数组元素不是对象 → `asJsonObject` 抛 IllegalStateException）。
     * 打标集只是参考数据，读不出来就当作「未打标」跳过——一条怪异记录不该毁掉整页 100 首。
     */
    internal fun unplayableIdsOf(items: JsonArray): Set<String> {
        val out = mutableSetOf<String>()
        items.forEach { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val privilege = o.get("privilege")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val st = privilege.get("st").stIntOrNull() ?: return@forEach
            if (st == 0) return@forEach
            val id = o.get("id").idString() ?: return@forEach
            out.add(NetworkSongId.forNetworkSong(METING_SOURCE, id))
        }
        return out
    }
}
