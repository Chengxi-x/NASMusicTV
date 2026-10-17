package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-7 **语言镜像**（SharedPreferences 双写）的一致性测试。
 *
 * ⚠️ 2026-10-17 大改：本类原名 `ProviderMirrorTest`，承担的是「写 DataStore ⇒ 轮询等 `@Volatile`
 * provider 镜像收敛」的时序断言（DoD 曾写作 ≤1s）。那 4 条时序用例连同 `awaitMirror`
 * （含超时归因与线程现场取证）**已整体删除**，理由不是它们没抓到东西，而是**判据本身没有可比性**：
 * CI runner 的磁盘、CPU、影子框架与客户电视完全不同，「20ms 收敛」与「10s 不收敛」都只描述那台机器，
 * 不描述任何一台真机；而生产里根本没有"超时"这个概念——同步 getter 读到的永远是"当前镜像值"，
 * 直到它被刷新。历史上两次靠扩窗口（1s→3s→10s）治红，治的正是这个不可比的量。
 *
 * provider 镜像这一面改由**确定性门**守：[ProviderMirrorWiringGateTest]
 * （G1 启动接线 / G2 每个镜像字段的读侧·写侧·`@Volatile` / G3 解密守卫 / G4 负向自证），
 * 零时序、零抖动。真机上要不要量化"改完设置到镜像刷新"的耗时，是另行决策，⛔ 不再回到 CI 断言。
 *
 * 本类留下的两条**不依赖时序**：`setLanguage` 是同步双写（SharedPreferences `edit().apply()` 与
 * DataStore 各写一次），读侧直接查镜像——「写完立刻能读到」是确定性契约，与调度快慢无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageMirrorTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `language mirror double write`() = runBlocking {
        prefs.setLanguage("zh")
        // 镜像立即生效（SharedPreferences 同步写）
        assertEquals("zh", prefs.getLanguageSync())
        // DataStore 事实源一致
        assertEquals("zh", prefs.language.first())
    }

    @Test
    fun `language mirror migration from datastore`() = runBlocking {
        // 模拟老版本：DataStore 已有值，镜像为空
        prefs.setLanguage("en")
        // 手动清空镜像（直接构造的实例 mirrorPrefs 与被测实例相同文件名）
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("language_mirror", Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals("system", prefs.getLanguageSync())  // 迁移前：镜像空回退默认
        prefs.migrateLanguageMirrorIfNeeded()
        assertEquals("en", prefs.getLanguageSync())    // 迁移后：镜像从 DataStore 补写
    }
}
