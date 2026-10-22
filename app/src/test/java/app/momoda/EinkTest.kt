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
        assertEquals("13", EinkManagerEink.duCode("6.1.75"))
        assertEquals("14", EinkManagerEink.duCode("4.19.232"))
        assertNull(EinkManagerEink.duCode("5.10.110"))
        assertNull(EinkManagerEink.duCode(""))
    }
}

class EinkPlatformTest {
    @Test fun rockchip() {
        val p = EinkManagerEink.platform(rkEbook = true, brand = "HANVON", hardware = "rk30board", kernel = "6.1.75")!!
        assertEquals("13", p.du)
        assertEquals("sys.ebook.one_full_mode_timeline", p.fullFrameProp)
    }

    @Test fun hanvonQualcomm() {
        val p = EinkManagerEink.platform(rkEbook = false, brand = "HANVON", hardware = "qcom", kernel = "5.15.153-android13-8")!!
        assertEquals("13", p.du)
        assertEquals("sys.eink.one_full_mode_timeline", p.fullFrameProp)
    }

    @Test fun othersUntouched() {
        assertNull(EinkManagerEink.platform(rkEbook = false, brand = "HANVON", hardware = "mt6789", kernel = "5.15"))
        assertNull(EinkManagerEink.platform(rkEbook = false, brand = "google", hardware = "qcom", kernel = "5.15"))
        assertNull(EinkManagerEink.platform(rkEbook = false, brand = "onyx", hardware = "qcom", kernel = "4.19"))
    }
}

class ModeKeeperRemapTest {
    /** 汉王高通平台：setMode("13") 实际写的是 32，夜间模式是 33。 */
    @Test fun systemRemapsCode() {
        var mode = "26"
        val writes = mutableListOf<String>()
        val k = ModeKeeper({ mode }, { mode = if (it == "13") "32" else it; writes += it })
        k.enter("13")
        assertEquals("32", mode)
        repeat(50) { k.enter("13") } // 每次按键
        assertEquals(listOf("13"), writes)
        k.leave()
        assertEquals("26", mode)
    }

    @Test fun systemOverrideStillDetected() {
        var mode = "26"
        val k = ModeKeeper({ mode }, { mode = if (it == "13") "32" else it })
        k.enter("13")
        mode = "26" // 系统按应用设置又改回去了
        k.enter("13")
        assertEquals("32", mode)
        k.leave()
        assertEquals("26", mode)
    }
}
