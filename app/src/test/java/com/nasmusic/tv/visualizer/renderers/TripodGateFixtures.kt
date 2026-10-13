package com.nasmusic.tv.visualizer.renderers

/**
 * E46 闸门测试（T7–T31）的**共用夹具**。
 *
 * ⭐ 启动顺序照原型 `boot()`（§十四，与 `TripodFogRenderer.boot` 同一份纪律）：
 * `layout → buildCum → setGrabKnots → syncRouteEpoch(true) → genBuildings`。
 * 少一步都会让闸门读到**没建好的世界**：`escG` 为 null ⇒ `escEnv` 恒 0，T7 的判据区整段错位；
 * `z0 = r()·GTOT` 在 `buildCum` 之前取会读到 `GTOT = 0`，整条天际线钉在原点。
 *
 * ⚠️ 与 [TripodFogGoldenTest] 的 `setup()` **故意不同**：那边还要复刻滑杆的 after 回调
 * （`state` 探针读 `varSwitches` 计数），闸门不读那份状态，多余的往返只会让 `CURVAR` 起点漂移。
 */
internal object TripodGateFixtures {

    const val W = 960.0
    const val H = 540.0

    fun boot(bakeBuildings: Boolean = true): TripodFogWorld {
        val world = TripodFogWorld()
        TripodFogMath.layout(world, W, H)
        TripodGait.buildCum(world)
        TripodFogMath.setGrabKnots(world)
        TripodFogMath.syncRouteEpoch(world, true)
        // ⚠️ genBuildings 里就带烘焙（`b.bmp = bakeBuilding(b)`，原型同构）⇒ 48 栋一次过
        if (bakeBuildings) TripodFogBake.genBuildings(world, world.params)
        return world
    }

    /** 一圈扫帧：`t` 从 0 **含** `LOOP`（接缝那一帧必须被扫到，T4/T17/T19 都判它）。 */
    inline fun frames(world: TripodFogWorld, step: Double, body: (Double, TripodScene) -> Unit) {
        val loop = world.params.loop
        var t = 0.0
        while (t <= loop + 1e-9) {
            body(t, TripodGait.sceneState(world, t))
            t += step
        }
    }

    /** 对比度闸门（原型 `judged` 的雾分量）：机器在雾里本来就不该看得见 ⇒ 不判。 */
    fun visibleEnough(world: TripodFogWorld, st: TripodScene): Double =
        TripodFogMath.contrastAt(st.dist, st.d0, world.params, world.palette)

    /** 未加仰角的地平线（T8 的保守口径：仰角只会更宽松）。 */
    fun hzBase(world: TripodFogWorld): Double = world.cam.h * world.params.horizonK
}

/**
 * 闸门侧的位图槽表（生产那份 `TripodSlotTable` 是文件私有的，⛔ 测试依赖私有实现细节）。
 *
 * ⚠️ 与生产同一语义：按**对象身份**返回槽位。⛔ 按下标 —— `TripodBuilding.i` 是**带内**下标，
 *    中景 `i = 0` 与近景 `i = 0` 会撞进同一个槽，建筑层互相贴错脸。
 */
internal class TripodGateBmpTable : TripodBmpTable {
    private val keys = arrayOfNulls<Any>(TripodSlot.COUNT)
    private var next = TripodSlot.BUILDING0

    fun put(bmp: Any?): Int {
        if (bmp == null) return TripodSlot.NONE
        val found = slotOf(bmp)
        if (found >= 0) return found
        if (next >= TripodSlot.COUNT) return TripodSlot.NONE
        keys[next] = bmp
        return next.also { next++ }
    }

    /** 雾条 / grade / 人群这些非建筑槽（闸门要让 BLIT 真的落笔时才用）。 */
    fun putAt(slot: Int, bmp: Any?) {
        if (slot in 0 until TripodSlot.COUNT) keys[slot] = bmp
    }

    override fun slotOf(bmp: Any?): Int {
        if (bmp == null) return TripodSlot.NONE
        for (i in 0 until TripodSlot.COUNT) if (keys[i] === bmp) return i
        return TripodSlot.NONE
    }
}
