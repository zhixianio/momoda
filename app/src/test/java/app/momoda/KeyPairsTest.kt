package app.momoda

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPairsTest {

    @Test
    fun upWithoutDownHereIsStray() {
        // 命令面板里按下 Enter 选了“打开文档”，Enter 的抬起送到了切换器的搜索框
        val keys = KeyPairs()
        assertFalse(keys.up(66))
    }

    @Test
    fun pairedUpPassesOnce() {
        val keys = KeyPairs()
        keys.down(29)
        keys.down(29) // 长按的重复按下
        assertTrue(keys.up(29))
        assertFalse(keys.up(29))
    }
}
