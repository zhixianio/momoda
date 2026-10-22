package app.momoda

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * 墨水屏厂商接口的薄适配层（docs/eink-sdk.md）。
 * - 打字时切到更跟手的刷新模式，离开墨墨打时恢复系统原来的模式；
 * - 停笔后全刷一次，清掉快速刷新留下的残影。
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
    /** 这台设备支持的清残影方式。 */
    val cleanStyles: List<CleanStyle>
    /**
     * 清残影：用 [style] 的波形把整屏刷一次。[view] 用来发出新的一帧。
     * 在本来就要整屏重画（翻页、换文档）的那一帧之前调用，就和那次重画合成一次。
     */
    fun clean(view: View, style: CleanStyle)
    /** 诊断信息里的一行。 */
    fun describe(): String
}

/** 打字时用的刷新模式。具体对应哪个波形由各家实现决定。 */
enum class TypingRefresh(val label: String) {
    SYSTEM("跟随系统"),
    AUTO("自动"),
    /** 灰阶局刷（Rockchip PART_GL16）：只刷变了的像素，16 级灰，不闪，比极速慢。 */
    GRAY("灰阶局刷"),
    DU("极速"),
    A2("黑白"),
}

object NoEink : EinkController {
    override val vendor: String? = null
    override val typingModes = listOf(TypingRefresh.SYSTEM)
    override fun enter(mode: TypingRefresh) = Unit
    override fun leave() = Unit
    override val cleanStyles = listOf(CleanStyle.FULL)
    override fun clean(view: View, style: CleanStyle) = Unit
    override fun describe() = "墨水屏接口：无"
}

/** 清残影的方式：全刷会整屏黑白翻转一次；灰阶几种是全屏刷一遍灰阶波形，不翻转，清得没那么干净。 */
enum class CleanStyle(val label: String) {
    FULL("全刷（会闪）"),
    GL16("灰阶 GL16"),
    GLR16("灰阶 GLR16"),
    GLD16("灰阶 GLD16"),
}

/**
 * 临时换一个刷新模式，刷完一帧再换回来。纯逻辑，单独测试。
 * 换回之前，别处（每次按键的 [ModeKeeper.enter]）不要去“纠正”模式，否则会把临时模式当成系统原来的模式记下。
 */
class TransientMode(private val get: () -> String?, private val set: (String) -> Unit) {
    private var previous: String? = null
    private var temporary: String? = null

    val active get() = temporary != null

    fun begin(mode: String) {
        if (temporary == null) previous = get() ?: return
        if (get() != mode) set(mode)
        temporary = mode
    }

    /** 换回进入前的模式；屏幕已经被别人改成别的模式就不动。 */
    fun finish() {
        val t = temporary ?: return
        val p = previous
        temporary = null
        previous = null
        if (p != null && p != t && get() == t) set(p)
    }
}

object Eink {
    /** 按厂商挑实现；任何一步失败都退回 [NoEink]。 */
    fun detect(context: Context): EinkController =
        runCatching { RockchipEink.create(context) }
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

    /** 切到 [target]；null 表示不改（等同于 [leave]）。已经切好时不写任何东西。 */
    fun enter(target: String?) {
        if (target == null) { leave(); return }
        val now = get() ?: return
        val applied = store.applied
        if (now == target && applied == target) return
        // 第一次进入，或者我们设过之后被系统、用户改成了别的：以现在的为“原来的模式”
        if (applied == null || now != applied) store.original = now
        if (now != target) set(target)
        if (applied != target) store.applied = target
    }

    /** 恢复原来的模式。只在屏幕还是我们设的模式时才改回去：别人改过就不动它。 */
    fun leave() {
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
 * Rockchip 电子书平台（汉王 N10 Pro 等，RK3566/RK3576）：系统服务 "eink"，
 * 类 android.os.EinkManager（隐藏接口，反射调用）。
 * setMode 写的是 sys.eink.mode（不持久化，重启复位）；sendOneFullFrame 让下一帧用 GC16 全刷。
 */
class RockchipEink private constructor(
    private val manager: Any,
    private val getMode: Method,
    private val sendOneFullFrame: Method?,
    private val keeper: ModeKeeper,
    private val transient: TransientMode,
) : EinkController {

    override val vendor = "${Build.MANUFACTURER} ${Build.MODEL}（Rockchip）"
    private val du = duCode(System.getProperty("os.version").orEmpty())
    override val typingModes = listOfNotNull(
        TypingRefresh.SYSTEM, TypingRefresh.AUTO, if (du != null) TypingRefresh.DU else null, TypingRefresh.A2, TypingRefresh.GRAY,
    )
    override val cleanStyles = listOfNotNull(if (sendOneFullFrame != null) CleanStyle.FULL else null, CleanStyle.GL16, CleanStyle.GLR16, CleanStyle.GLD16)

    private fun code(mode: TypingRefresh): String? = when (mode) {
        TypingRefresh.SYSTEM -> null
        TypingRefresh.AUTO -> EPD_AUTO
        TypingRefresh.DU -> du ?: EPD_AUTO
        TypingRefresh.A2 -> EPD_A2
        TypingRefresh.GRAY -> EPD_PART_GL16
    }

    override fun enter(mode: TypingRefresh) {
        // 正在用临时模式清残影：等它换回来再说
        if (transient.active) return
        runCatching { keeper.enter(code(mode)) }.onFailure { Log.w("momoda", "切换刷新模式失败", it) }
    }

    override fun leave() {
        finishTransient()
        runCatching { keeper.leave() }.onFailure { Log.w("momoda", "恢复刷新模式失败", it) }
    }

    override fun clean(view: View, style: CleanStyle) {
        val mode = when (style) {
            CleanStyle.FULL -> { fullFrame(view); return }
            CleanStyle.GL16 -> EPD_FULL_GL16
            CleanStyle.GLR16 -> EPD_FULL_GLR16
            CleanStyle.GLD16 -> EPD_FULL_GLD16
        }
        runCatching {
            // 显示服务在提交一帧时读 sys.eink.mode，决定这一帧用什么波形：先换模式，再让整个窗口重画一帧，
            // 这一帧画完、交给屏幕之后再换回来
            transient.begin(mode)
            val root = view.rootView
            root.invalidate()
            restoreView = root
            root.viewTreeObserver.addOnDrawListener(drawn)
            handler.removeCallbacks(restore)
            handler.postDelayed(restore, RESTORE_TIMEOUT_MS) // 万一没有画出这一帧，也要换回来
            Log.i("momoda", "清残影：临时模式 $mode")
        }.onFailure { Log.w("momoda", "清残影失败", it); finishTransient() }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var restoreView: View? = null
    private val restore = Runnable { finishTransient() }
    private val drawn = object : android.view.ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            // 画完了；交给屏幕还要一点时间。不能在 onDraw 里摘掉监听器
            handler.post { restoreView?.viewTreeObserver?.removeOnDrawListener(this) }
            handler.removeCallbacks(restore)
            handler.postDelayed(restore, RESTORE_AFTER_DRAW_MS)
        }
    }

    private fun finishTransient() {
        handler.removeCallbacks(restore)
        restoreView?.let { v -> handler.post { v.viewTreeObserver.removeOnDrawListener(drawn) } }
        restoreView = null
        runCatching { transient.finish() }.onFailure { Log.w("momoda", "换回刷新模式失败", it) }
    }

    private fun fullFrame(view: View) {
        val m = sendOneFullFrame ?: return
        runCatching {
            // sendOneFullFrame 把进程内的计数写进 sys.ebook.one_full_mode_timeline，显示服务看到它变了就全刷下一帧。
            // 计数每个进程从 1 开始，可能刚好等于属性里别的 App 留下的值：没变就再调一次
            val before = getProp(FULL_FRAME_PROP)
            m.invoke(manager)
            if (before != null && getProp(FULL_FRAME_PROP) == before) m.invoke(manager)
            // 全刷标记只作用于下一帧：整个窗口重画一次，把这一帧发出去
            view.rootView.invalidate()
        }.onFailure { Log.w("momoda", "全刷失败", it) }
    }

    private fun currentMode(): String? = runCatching { getMode.invoke(manager) as String? }.getOrNull()

    override fun describe() = "墨水屏接口：$vendor EinkManager，当前模式 ${currentMode()}，全刷${if (sendOneFullFrame != null) "可用" else "不可用"}"

    companion object {
        // 模式编号见 docs/eink-sdk.md。AUTO、A2 在各内核分支都一样；DU 不一样：
        // 内核 6.1 是 13（汉王 N10 Pro 实测，“极速”就是它），4.19 是 14（13 在那里是 A2_DITHER）。
        // 框架里 EinkManager.EinkMode 的常量是旧的 4.19 编号，和 6.1 内核对不上，不能拿来用
        const val EPD_AUTO = "0"
        const val EPD_A2 = "12"
        // 全屏灰阶（3–5）和灰阶局刷（8）在 4.19、6.1 两个分支里编号相同
        const val EPD_FULL_GL16 = "3"
        const val EPD_FULL_GLR16 = "4"
        const val EPD_FULL_GLD16 = "5"
        const val EPD_PART_GL16 = "8"

        /** 换了临时模式的那一帧画完以后，再等多久换回来（留给显示服务提交这一帧）。 */
        const val RESTORE_AFTER_DRAW_MS = 300L
        private const val RESTORE_TIMEOUT_MS = 1500L

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
        private const val FULL_FRAME_PROP = "sys.ebook.one_full_mode_timeline"

        fun create(context: Context): RockchipEink? {
            // 只认 Rockchip 电子书固件：别的设备上不去碰名为 "eink" 的服务
            if (getProp("persist.sys.rk-ebook") != "true" && getProp("ro.rk.ebook_settings") != "true") return null
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
            return RockchipEink(manager, get, full, keeper, TransientMode(read, write))
        }

        private val systemPropertiesGet: Method? by lazy {
            runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
        }

        private fun getProp(key: String): String? = runCatching { systemPropertiesGet?.invoke(null, key) as String? }.getOrNull()
    }
}

/**
 * 停笔清残影：快速刷新（DU、A2、AUTO）打字久了会留下底纹。
 * 停笔 [delayMs] 毫秒后全刷一次；正在拼写（输入法有未上屏的字）时不刷，再按键就重新计时。
 * 只有屏幕真的变过（打过字、翻过页）才刷，看着不动时不会每隔一会儿闪一下。纯逻辑，单独测试。
 */
class GhostCleaner(var delayMs: Long) {
    /** 自上次全刷以来屏幕变化的程度：打一个字 +1，翻一页 +[PAGE]。 */
    var dirt = 0
        private set
    private var lastActivity = 0L

    /** 按了键（包括输入法拼写中的按键，文字不一定变）。 */
    fun onKey(now: Long) { lastActivity = now }

    /** 文字变了。 */
    fun onEdit(now: Long) { dirt++; lastActivity = now }

    /** 整屏换了内容（翻页、换文档）。 */
    fun onPage(now: Long) { dirt += PAGE; lastActivity = now }

    fun onFullRefresh() { dirt = 0 }

    /** 从现在起还要等多久；null 表示不用刷。 */
    fun waitMs(now: Long): Long? {
        if (delayMs <= 0 || dirt < MIN_DIRT) return null
        return (lastActivity + delayMs - now).coerceAtLeast(0)
    }

    /** 到点时再确认一次：正在拼写就不刷，等下一次按键后重新计时。 */
    fun due(now: Long, composing: Boolean): Boolean = !composing && waitMs(now) == 0L

    companion object {
        const val PAGE = 20
        /** 改了几个字就不值得闪一下。 */
        const val MIN_DIRT = 3
        val DELAYS = listOf(0L, 5_000L, 10_000L, 30_000L, 60_000L)
    }
}
