package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkArtistRoutingTest {

    /** 不支持艺人的源（Jamendo / 百度网盘的形状）：只实现既有三个方法 */
    private class PlainService : NetworkMusicService {
        override val sourceId = "plain"
        override suspend fun search(keyword: String, limit: Int) = emptyList<Song>()
        override suspend fun resolvePlayUrl(song: Song) = "http://x/1.mp3"
        override suspend fun resolveLyrics(song: Song) = null
        override suspend fun getPlaylist(playlistId: String) = emptyList<Song>()
    }

    private class ArtistService : NetworkMusicService {
        override val sourceId = "meting"
        override suspend fun search(keyword: String, limit: Int) = emptyList<Song>()
        override suspend fun resolvePlayUrl(song: Song) = "http://x/1.mp3"
        override suspend fun resolveLyrics(song: Song) = null
        override suspend fun getPlaylist(playlistId: String) = emptyList<Song>()
        override suspend fun findArtist(name: String) = NetworkArtistInfo("6452", name, 566)
        override suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int) =
            ArtistSongsPage(
                songs = listOf(Song(id = "ntwk_meting_$offset", title = "t$offset",
                    networkSource = "meting", networkId = offset.toString(), isNetworkSong = true)),
                total = 566, hasMore = offset < 500, unplayableIds = setOf("ntwk_meting_$offset")
            )
    }

    @Test
    fun `按默认源路由且不 fallback`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to ArtistService(), "plain" to PlainService()),
            defaultSourceProvider = { "meting" }
        )
        assertEquals("6452", mgr.findArtist("周杰伦")?.id)
        val page = mgr.getArtistSongs("6452", offset = 0)!!
        assertEquals(566, page.total)
        assertTrue(page.hasMore)
        assertEquals(setOf("ntwk_meting_0"), page.unplayableIds)
    }

    @Test
    fun `不支持艺人的源返回 null 而不抛异常`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("plain" to PlainService()),
            defaultSourceProvider = { "plain" }
        )
        assertNull(mgr.findArtist("周杰伦"))
        assertNull(mgr.getArtistSongs("1", 0))
    }

    @Test
    fun `默认源未注册时返回 null（与 resolvePlayUrl 的未注册源分支同语义）`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to ArtistService()),
            defaultSourceProvider = { "ghost" }
        )
        assertNull(mgr.findArtist("周杰伦"))
    }
}
