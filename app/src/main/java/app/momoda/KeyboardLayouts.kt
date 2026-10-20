package app.momoda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 向系统登记墨墨打提供的实体键盘布局（res/xml/keyboard_layouts.xml）。
 * 系统只用它的 meta-data 找布局文件，这里不需要处理任何广播。
 */
class KeyboardLayouts : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
