package app.momoda

import android.content.Context
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice

/** 有没有接着实体键盘（USB、蓝牙）。 */
object HardKeyboard {

    /**
     * 先看系统配置；有的电纸书接上键盘后配置不变（汉王 Clear 7 Turbo 上没法确认），
     * 再看输入设备里有没有真正的全键盘（机身按键、触摸屏是“非字母键盘”，软键盘是虚拟设备，都不算）。
     */
    fun connected(context: Context): Boolean {
        val c = context.resources.configuration
        if (c.keyboard == Configuration.KEYBOARD_QWERTY && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) return true
        val im = context.getSystemService(InputManager::class.java) ?: return false
        return im.inputDeviceIds.any { id ->
            im.getInputDevice(id)?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true
        }
    }
}
