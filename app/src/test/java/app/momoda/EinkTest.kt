package app.momoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeKeeperTest {

    /** 模拟设备上的 sys.eink.mode，记下每一次写入。 */
    private class Device(var mode: String?) {
        val writes = mutableListOf<String>()
        fun keeper(store: ModeKeeper.Store = ModeKeeper.Store.Memory()) =
            ModeKeeper({ mode }, { mode = it; writes += it }, store)
    }

    @Test fun entersAndRestores() {
        val d = Device("13")
        val k = d.keeper()
        k.enter("0")
        assertEquals("0", d.mode)
        k.leave()
        assertEquals("13", d.mode)
        assertEquals(listOf("0", "13"), d.writes)
    }

    @Test fun sameModeWritesNothing() {
        val d = Device("0")
        val k = d.keeper()
        k.enter("0")
        k.leave()
        assertTrue(d.writes.isEmpty())
    }

    @Test fun repeatedEnterKeepsFirstOriginal() {
        val d = Device("13")
        val k = d.keeper()
        k.enter("0")
        k.enter("12") // 在设置里换了模式
        k.enter("12")
        k.leave()
        assertEquals("13", d.mode)
    }

    @Test fun changedByOthersWhileInsideBecomesOriginal() {
        val d = Device("13")
        val k = d.keeper()
        k.enter("0")
        d.mode = "7" // 用户在下拉菜单里改成了普通模式
        k.enter("0") // 拿回焦点时再设一次
        k.leave()
        assertEquals("7", d.mode)
    }

    @Test fun leaveDoesNotClobberOthersChange() {
        val d = Device("13")
        val k = d.keeper()
        k.enter("0")
        d.mode = "7"
        k.leave()
        assertEquals("7", d.mode)
    }

    @Test fun systemTargetMeansLeave() {
        val d = Device("13")
        val k = d.keeper()
        k.enter("0")
        k.enter(null) // 设置里改成“跟随系统”
        assertEquals("13", d.mode)
    }

    @Test fun leaveTwiceIsHarmless() {
        val d = Device("13")
        val k = d.keeper()
        k.leave()
        k.enter("0")
        k.leave()
        k.leave()
        assertEquals(listOf("0", "13"), d.writes)
    }

    @Test fun unreadableModeChangesNothing() {
        val d = Device(null)
        val k = d.keeper()
        k.enter("0")
        k.leave()
        assertTrue(d.writes.isEmpty())
    }

    @Test fun survivesProcessDeath() {
        val d = Device("13")
        val store = ModeKeeper.Store.Memory() // 代表 SharedPreferences
        d.keeper(store).enter("0")
        // 进程被杀，没来得及 leave；下次启动是新的 ModeKeeper
        val next = d.keeper(store)
        next.enter("0")
        next.leave()
        assertEquals("13", d.mode)
    }
}

class GhostCleanerTest {

    @Test fun noCleanWithoutChanges() {
        val g = GhostCleaner(10_000)
        g.onKey(0)
        assertNull(g.waitMs(0))
    }

    @Test fun fewEditsNotWorthAFlash() {
        val g = GhostCleaner(10_000)
        repeat(GhostCleaner.MIN_DIRT - 1) { g.onEdit(it.toLong()) }
        assertNull(g.waitMs(100_000))
    }

    @Test fun cleansAfterIdle() {
        val g = GhostCleaner(10_000)
        repeat(10) { g.onEdit(1_000L + it) }
        assertEquals(10_000L - 1, g.waitMs(1_010))
        assertFalse(g.due(5_000, composing = false))
        assertTrue(g.due(11_009, composing = false))
        g.onFullRefresh()
        assertNull(g.waitMs(20_000))
    }

    @Test fun keyPressRestartsTimer() {
        val g = GhostCleaner(10_000)
        repeat(10) { g.onEdit(0) }
        g.onKey(8_000) // 输入法拼写中的按键，文字没变
        assertFalse(g.due(10_000, composing = false))
        assertTrue(g.due(18_000, composing = false))
    }

    @Test fun neverWhileComposing() {
        val g = GhostCleaner(5_000)
        repeat(10) { g.onEdit(0) }
        assertFalse(g.due(60_000, composing = true))
        assertTrue(g.due(60_000, composing = false))
    }

    @Test fun pageTurnAloneIsEnough() {
        val g = GhostCleaner(5_000)
        g.onPage(0)
        assertEquals(5_000L, g.waitMs(0))
    }

    @Test fun disabled() {
        val g = GhostCleaner(0)
        g.onPage(0)
        assertNull(g.waitMs(100_000))
    }
}

class ModeKeeperRepeatTest {
    @Test fun repeatedEnterWritesStoreOnce() {
        var mode = "13"
        var storeWrites = 0
        val store = object : ModeKeeper.Store {
            override var original: String? = null
                set(v) { field = v; storeWrites++ }
            override var applied: String? = null
                set(v) { field = v; storeWrites++ }
        }
        val k = ModeKeeper({ mode }, { mode = it }, store)
        k.enter("0")
        val after = storeWrites
        repeat(100) { k.enter("0") } // 每次按键都调用
        assertEquals(after, storeWrites)
        mode = "13" // 系统在我们之后又改回去了
        k.enter("0")
        assertEquals("0", mode)
        k.leave()
        assertEquals("13", mode)
    }
}

class ModeKeeperSystemModeTest {
    @Test fun followSystemWritesNothingPerKey() {
        var writes = 0
        val store = object : ModeKeeper.Store {
            override var original: String? = null
                set(v) { field = v; writes++ }
            override var applied: String? = null
                set(v) { field = v; writes++ }
        }
        val k = ModeKeeper({ "13" }, { error("不该改模式") }, store)
        repeat(100) { k.enter(null) } // “跟随系统”时每次按键都调用
        assertEquals(0, writes)
    }
}

class RockchipModeCodeTest {
    @Test fun duDependsOnKernelBranch() {
        assertEquals("13", RockchipEink.duCode("6.1.75"))
        assertEquals("14", RockchipEink.duCode("4.19.232"))
        assertNull(RockchipEink.duCode("5.10.110"))
        assertNull(RockchipEink.duCode(""))
    }
}

class TransientModeTest {
    private var mode: String? = "13"
    private val writes = mutableListOf<String>()
    private val t = TransientMode({ mode }, { mode = it; writes += it })

    @Test fun switchesAndRestores() {
        t.begin("3")
        assertTrue(t.active)
        assertEquals("3", mode)
        t.finish()
        assertFalse(t.active)
        assertEquals("13", mode)
        assertEquals(listOf("3", "13"), writes)
    }

    @Test fun secondBeginKeepsFirstPrevious() {
        t.begin("3")
        t.begin("4") // 上一次还没换回来又清了一次
        t.finish()
        assertEquals("13", mode)
    }

    @Test fun doesNotClobberOthersChange() {
        t.begin("3")
        mode = "7" // 用户在下拉菜单里改了
        t.finish()
        assertEquals("7", mode)
    }

    @Test fun finishWithoutBeginIsHarmless() {
        t.finish()
        assertTrue(writes.isEmpty())
    }

    @Test fun unreadableModeDoesNothing() {
        mode = null
        t.begin("3")
        assertFalse(t.active)
        assertTrue(writes.isEmpty())
    }
}
