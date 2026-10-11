package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 替换链决策测试（§6 验收 15 + §3.8 第 2/3 步的顺序约束）。
 *
 * 纯 JVM 测试，不需要 Robolectric（无 Android 依赖）。
 */
class ReplaceChainTest {

    /**
     * 红线 1：id 一律由 [NetworkSongId] 生成，⛔ 不在 fixture 里手拼字符串
     *（产物与 `ntwk_meting_<netId>` 逐字符相同，下面的字面量断言因此仍然成立）。
     */
    private fun song(id: String) = Song(
        id = NetworkSongId.forNetworkSong("meting", id), title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    @Test
    fun `普通失败按序给出两层——同源在前`() {
        assertEquals(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE),
            ReplaceChain.plan(song("1").id, lastReplacedId = null)
        )
        assertEquals(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE),
            ReplaceChain.plan(song("1").id, lastReplacedId = song("9").id)
        )
    }

    /** §6 验收 15：替换产物仍失败 ⇒ 不再二次替换（`PlayerViewModel.kt:473-479` 的既有语义） */
    @Test
    fun `已是替换产物 ⇒ 层序为空，一层都不跑`() {
        assertEquals(emptyList<ReplaceLayer>(), ReplaceChain.plan(song("2").id, lastReplacedId = song("2").id))
    }

    @Test
    fun `第一个成功的层即停——后面的层一次都不请求`() = runBlocking {
        val tried = mutableListOf<ReplaceLayer>()
        val hit = ReplaceChain.run(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)
        ) { layer ->
            tried += layer
            if (layer == ReplaceLayer.SAME_SOURCE) song("2") else null
        }
        assertEquals(ReplaceLayer.SAME_SOURCE, hit?.first)
        assertEquals("跨源一层根本不该被跑", listOf(ReplaceLayer.SAME_SOURCE), tried)
    }

    @Test
    fun `同源落空才轮到跨源`() = runBlocking {
        val tried = mutableListOf<ReplaceLayer>()
        val hit = ReplaceChain.run(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)
        ) { layer ->
            tried += layer
            if (layer == ReplaceLayer.CROSS_SOURCE) song("3") else null
        }
        assertEquals(ReplaceLayer.CROSS_SOURCE, hit?.first)
        assertEquals(listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE), tried)
    }

    @Test
    fun `两层都落空返回 null（调用方据此跳下一首）`() = runBlocking {
        assertNull(ReplaceChain.run(ReplaceChain.plan(song("1").id, null)) { null })
        assertNull(ReplaceChain.run(ReplaceChain.plan(song("1").id, song("1").id)) { error("不该被调用") })
    }
}
