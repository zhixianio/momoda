package app.momoda

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * 墨水屏厂商接口的薄适配层（docs/eink-sdk.md）。
 * - 打字时切到更跟手的刷新模式，离开墨墨打时恢复系统原来的模式；
 * - 手动全刷一次（GC16），清掉快速刷新留下的残影。
 * 识别不出来的设备用 [NoEink]：什么都不做，非墨水屏设备上没有任何副作用。
 */
interface EinkController {
    /** 设置里显示的设备名；null 表示不能控制刷新。 */
    val vendor: String?
    /** 这台设备支持的打字刷新模式（第一个是“跟随系统”）。 */
    val typingModes: List<TypingRefresh>
    /**
     * 进入写作：记下系统当前的模式，切到 [mode]。[TypingRefresh.SYSTEM] 等同于 [leave]。
     * 已经是这个模式时只读一次系统属性，每次按键都可以调用（系统可能在我们之后又改了模式）。
     */
    fun enter(mode: TypingRefresh)
    /** 离开写作：恢复进入时记下的模式。可以重复调用。 */
    fun leave()
    /** 全屏用 GC16 刷一次，清残影（会闪一下）。[view] 用来发出新的一帧。 */
    fun fullRefresh(view: View)
    /** 诊断信息里的一行。 */
    fun describe(): String
}

/** 打字时用的刷新模式。具体对应哪个波形由各家实现决定。 */
enum class TypingRefresh(val label: String) {
    SYSTEM("跟随系统"),
    AUTO("自动"),
    DU("极速"),
    A2("黑白"),
}

object NoEink : EinkController {
    override val vendor: String? = null
    override val typingModes = listOf(TypingRefresh.SYSTEM)
    override fun enter(mode: TypingRefresh) = Unit
    override fun leave() = Unit
    override fun fullRefresh(view: View) = Unit
    override fun describe() = "墨水屏接口：无"
}

object Eink {
    /** 按厂商挑实现；任何一步失败都退回 [NoEink]。 */
    fun detect(context: Context): EinkController =
        runCatching { EinkManagerEink.create(context) }
            .onFailure { Log.w("momoda", "墨水屏接口不可用", it) }
            .getOrNull() ?: NoEink
}

/**
 * 记住“进入之前系统是什么模式”，离开时恢复。纯逻辑，单独测试（[get]/[set] 是对设备的读写）。
 * 保存的状态交给 [store]：进程被杀、崩溃后下次启动还能知道原来的模式。
 */
class ModeKeeper(
    private val get: () -> String?,
    private val set: (String) -> Unit,
    private val store: Store = Store.Memory(),
) {
    interface Store {
        var original: String?
        var applied: String?
        class Memory : Store {
            override var original: String? = null
            override var applied: String? = null
        }
    }

    /** 这个进程里最后一次要求的模式；[ModeKeeper.Store.applied] 是设下去之后实际读回来的编号。 */
    private var requested: String? = null

    /**
     * 切到 [target]；null 表示不改（等同于 [leave]）。已经切好时不写任何东西。
     * 系统可能把编号换成等价的另一个（汉王高通平台：极速写 13，读回来是 32，夜间模式是 33），
     * 所以“我们设的模式”记的是设完以后读回来的值。
     */
    fun enter(target: String?) {
        if (target == null) { leave(); return }
        val now = get() ?: return
        val applied = store.applied
        if (now == applied && requested == target) return
        // 第一次进入，或者我们设过之后被系统、用户改成了别的：以现在的为“原来的模式”
        if (applied == null || now != applied) store.original = now
        if (now != target) set(target)
        val actual = get() ?: target
        if (applied != actual) store.applied = actual
        requested = target
    }

    /** 恢复原来的模式。只在屏幕还是我们设的模式时才改回去：别人改过就不动它。 */
    fun leave() {
        requested = null
        val original = store.original
        val applied = store.applied
        if (original == null && applied == null) return
        store.original = null
        store.applied = null
        if (original == null || applied == null || original == applied) return
        if (get() == applied) set(original)
    }
}

/**
 * 带 android.os.EinkManager 的电子书固件（隐藏接口，反射调用）：系统服务 "eink"，
 * setMode 写的是 sys.eink.mode（不持久化，重启复位）；sendOneFullFrame 让下一帧用 GC16 全刷。
 * 同一个类在不同平台上模式编号不同，见 [Platform]。
 */
class EinkManagerEink private constructor(
    private val manager: Any,
    private val getMode: Method,
    private val sendOneFullFrame: Method?,
    private val keeper: ModeKeeper,
    private val platform: Platform,
) : EinkController {

    /**
     * 一种固件：各打字模式写给 setMode 的编号（null 表示不提供），以及 sendOneFullFrame 写的属性名。
     */
    data class Platform(
        val name: String,
        val auto: String?,
        val du: String?,
        val a2: String?,
        val fullFrameProp: String,
    )

    override val vendor = "${Build.BRAND} ${Build.MODEL}（${platform.name}）"
    override val typingModes = listOfNotNull(
        TypingRefresh.SYSTEM,
        if (platform.auto != null) TypingRefresh.AUTO else null,
        if (platform.du != null) TypingRefresh.DU else null,
        if (platform.a2 != null) TypingRefresh.A2 else null,
    )

    private fun code(mode: TypingRefresh): String? = when (mode) {
        TypingRefresh.SYSTEM -> null
        TypingRefresh.AUTO -> platform.auto
        TypingRefresh.DU -> platform.du
        TypingRefresh.A2 -> platform.a2
    }

    override fun enter(mode: TypingRefresh) {
        runCatching { keeper.enter(code(mode)) }.onFailure { Log.w("momoda", "切换刷新模式失败", it) }
    }

    override fun leave() {
        runCatching { keeper.leave() }.onFailure { Log.w("momoda", "恢复刷新模式失败", it) }
    }

    override fun fullRefresh(view: View) {
        val m = sendOneFullFrame ?: return
        runCatching {
            // sendOneFullFrame 把进程内的计数写进 one_full_mode_timeline 属性，显示服务看到它变了就全刷下一帧。
            // 计数每个进程从 1 开始，可能刚好等于属性里别的 App 留下的值：没变就再调一次
            val before = getProp(platform.fullFrameProp)
            m.invoke(manager)
            if (before != null && getProp(platform.fullFrameProp) == before) m.invoke(manager)
            // 全刷标记只作用于下一帧：整个窗口重画一次，把这一帧发出去
            view.rootView.invalidate()
        }.onFailure { Log.w("momoda", "全刷失败", it) }
    }

    private fun currentMode(): String? = runCatching { getMode.invoke(manager) as String? }.getOrNull()

    override fun describe() = "墨水屏接口：$vendor EinkManager，当前模式 ${currentMode()}，全刷${if (sendOneFullFrame != null) "可用" else "不可用"}"

    companion object {
        /**
         * 认出固件，给出模式编号（docs/eink-sdk.md）；认不出返回 null，什么都不碰。
         * - Rockchip 电子书固件（汉王 N10 Pro 等）：有 persist.sys.rk-ebook 或 ro.rk.ebook_settings。
         *   AUTO=0、A2=12；DU 按内核分支（见 [duCode]）。
         * - 汉王高通平台（Clear 7 Turbo，型号 P7）：品牌 HANVON、硬件 qcom。setMode 会把老编号换成本平台的：
         *   13（汉王“极速”）→ 32，夜间模式 33。
         * 框架里 EinkManager.EinkMode 的常量在两个平台上都和实际不完全对得上，不能拿来用。
         */
        fun platform(rkEbook: Boolean, brand: String, hardware: String, kernel: String): Platform? = when {
            rkEbook -> Platform("Rockchip", auto = "0", du = duCode(kernel), a2 = "12", fullFrameProp = "sys.ebook.one_full_mode_timeline")
            brand.equals("HANVON", ignoreCase = true) && hardware == "qcom" ->
                Platform("汉王高通", auto = "0", du = "13", a2 = "12", fullFrameProp = "sys.eink.one_full_mode_timeline")
            else -> null
        }

        /** 按内核版本（os.version，例如 "6.1.75"）给出 DU 的编号；认不出的内核返回 null，不提供这个选项。 */
        fun duCode(kernel: String): String? {
            val parts = kernel.split('.', '-')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return when {
                major == 4 && minor == 19 -> "14"
                major == 6 && minor == 1 -> "13"
                else -> null
            }
        }

        fun create(context: Context): EinkManagerEink? {
            // 只认得出来的固件：别的设备上不去碰名为 "eink" 的服务
            val rkEbook = getProp("persist.sys.rk-ebook") == "true" || getProp("ro.rk.ebook_settings") == "true"
            val platform = platform(rkEbook, Build.BRAND, Build.HARDWARE, System.getProperty("os.version").orEmpty()) ?: return null
            val manager = context.getSystemService("eink") ?: return null
            val cls = manager.javaClass
            val set = cls.getMethod("setMode", String::class.java)
            val get = cls.getMethod("getMode")
            val full = runCatching { cls.getMethod("sendOneFullFrame") }.getOrNull()
            val prefs = context.getSharedPreferences("eink", Context.MODE_PRIVATE)
            val store = object : ModeKeeper.Store {
                // commit：切换模式后进程随时可能被杀，下次启动要靠它恢复
                override var original: String?
                    get() = prefs.getString("original", null)
                    set(v) { prefs.edit().putString("original", v).commit() }
                override var applied: String?
                    get() = prefs.getString("applied", null)
                    set(v) { prefs.edit().putString("applied", v).commit() }
            }
            // 读模式优先直接读系统属性（不走 binder，每次按键都读得起），读不到再问系统服务
            val read = { getProp("sys.eink.mode")?.ifEmpty { null } ?: get.invoke(manager) as String? }
            val write = { mode: String -> Log.i("momoda", "墨水屏刷新模式 ${read()} → $mode"); set.invoke(manager, mode); Unit }
            val keeper = ModeKeeper(read, write, store)
            return EinkManagerEink(manager, get, full, keeper, platform)
        }

        private val systemPropertiesGet: Method? by lazy {
            runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
        }

        private fun getProp(key: String): String? = runCatching { systemPropertiesGet?.invoke(null, key) as String? }.getOrNull()
    }
}
