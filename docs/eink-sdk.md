# 墨水屏厂商接口调研

2026-10-22。目标：让打字更跟手、残影更少，并找出各品牌系统里其他能利用的能力。

标记：**【已验证】** 在真机上用墨墨打（普通第三方 App，targetSdk 34）实测过；**【已核对】** 读过设备上的系统框架代码，没有实际调用；**【资料】** 只来自文档、源码仓库或论坛，未在真机上验证。

代码在 `app/src/main/java/app/momoda/Eink.kt`：`EinkController` 接口，`RockchipEink` 一个实现，识别不出来的设备用 `NoEink`（什么都不做）。

## 汉王 N10 Pro（Rockchip RK3576，Android 14，固件 EBook-2.2.6）

### 刷新模式：可用【已验证】

- `context.getSystemService("eink")` 返回 `android.os.EinkManager`（Rockchip 电子书 SDK 的类，不在公开 SDK 里）。反射调用 `setMode(String)`、`getMode()`、`sendOneFullFrame()` 都能用，Android 14 的隐藏接口限制没有拦它，也不需要任何权限。
- 系统服务端 `EinkService.setProperty` 不检查调用者，直接 `SystemProperties.set`。`setMode` 写的是 `sys.eink.mode`：`sys.` 开头，**不持久化，重启复位**。
- 模式编号（`EinkManager.EinkMode`）：0 AUTO、1 OVERLAY（手写）、2 FULL_GC16（全刷）、3–6 FULL_GL16 等、7 PART_GC16（普通局刷）、8–11 PART_GL16 等、12 A2、13 DU；这台机器还多了 AUTO_DU、AUTO_DU4、DU4、A2_DITHER、A2_ENTER、PART_VEDIO、RESET。
- `setMode` 的副作用：除模式 15 外，没开相机时会把 `persist.ebook.bw_repair_en` 设成 1（这台机器上本来就是 1）。
- **汉王的“按应用刷新设置”会覆盖我们**：应用回到前台约 100ms 后，SystemUI 的 `EinkSettingsProvider` 按该应用在汉王设置里的刷新模式再设一次（日志：`packageName=app.momoda … refreshMode=13 … bNeedSetRefreshMode=true`），并发出非保护广播 `com.rockchip.eink.appcustom`。用户说的“极速”就是这里给墨墨打设的 13（DU）；桌面是 9。
  - 对策：`onResume`、拿回窗口焦点、**每次按键**都确认一次。按键时直接读系统属性 `sys.eink.mode`（不走 binder，微秒级），和目标不同才切。
- 恢复：`onPause` 时改回进入前的模式；只在屏幕还是我们设的模式时才改，用户或系统在中途改过就不动。进入前的模式存在 SharedPreferences（commit），进程被杀后下次启动还认得；崩溃处理器里也会恢复。即使墨墨打意外退出没来得及恢复，汉王切到下一个应用时 SystemUI 也会按那个应用的设置重设。

实测（adb 读 `sys.eink.mode`）：桌面 9 → 打开墨墨打先被设成 0 → SystemUI 改成 13 → 第一次按键改回 0 → 按 Home 时墨墨打改回 13 → 桌面的 SystemUI 改成 9。设置里切“极速 / 黑白 / 跟随系统 / 自动”分别得到 13 / 12 / 13（恢复）/ 0。

### 各模式对比

| 模式 | 编号 | 官方说明 | 打字时的预期 |
|---|---|---|---|
| 自动 AUTO | 0 | 为键盘这类要求快速响应的场景设计；按像素在 GC16 和 DU 之间选，多区域同时连续刷新，标称最快，16 级灰 | 默认值。连续打字时多个区域可以并行刷，不用等上一个字刷完 |
| 极速 DU | 13 | 只刷变化的像素，黑白两级波形，快 | 汉王“极速”。抗锯齿的灰边被压成黑白，残影累积明显 |
| 黑白 A2 | 12 | 最快的黑白波形，带抖动；进入前系统先用 DU 刷一次 | 最快，但字边缘有锯齿和抖动纹，灰色的页眉页脚也会变成网点 |
| 普通局刷 PART_GC16 | 7 | 16 级灰，无闪烁 | 最清楚、最慢，不提供给打字 |

**手感和残影要人眼看**：驱动的刷新耗时不对 App 开放（`/sys/devices/platform/ebc-dev/*` 都是 Permission denied，也没有相关日志），软件只能量到“按键到交给屏幕”这一段（Ctrl+Shift+L）。在设置“打字时刷新”里可以随时切换比较。

### 全刷清残影：可用【已验证调用；闪屏效果待人眼确认】

- `sendOneFullFrame()` 只是把进程内的计数写进 `sys.ebook.one_full_mode_timeline`，显示服务看到值变了，就把**下一帧**用 GC16 全刷；所以调用后要让窗口重画一帧（`rootView.invalidate()`）。
- 计数每个进程从 1 开始，可能刚好等于属性里其他 App 留下的值（汉王切换应用时也会调它，实测值 140、144…）：写完值没变就再调一次。
- 不改变当前模式，不需要“切到 GC16 刷一帧再切回”。
- 墨墨打的用法：停笔 10 秒（可选 5 / 30 / 60 秒或关）后全刷一次。改动少于 3 个字不刷；输入法有未上屏的拼音（编辑器里有 composing 区域）时不刷；每次按键（包括拼写中、文字没变的按键）都重新计时；有弹窗时不刷。翻页、连续模式下的滚动算一大块改动；换文档时如果有该清的残影就立刻全刷，和整屏重画合成一次。Ctrl+Shift+E 随时手动全刷。
- 局限：fcitx5 如果把拼音显示在自己的候选窗里（不是编辑器里的 composing 区域），墨墨打看不到“正在拼写”，只靠“按键后 N 秒”避开。

### 其他能力【已核对，未调用】

框架里汉王自己的类（`android.hwebook.*`）：

- `HvEinkManager.setEinkMode / getEinkMode`：只是 `EinkManager.setMode / getMode` 的包装，没有额外价值。
- `HanvonEbk`：`setColdBrightness / setWarmBrightness / setCwlBrightness`（前光冷暖）、`disableKeys`（屏蔽按键）、`enterHwMode`（手写模式）、`setSystemSleepMode`、`onceUpdate / forceUpdate` 等。几乎全是 JNI（`libHANVONEBK_jni.so`）直接操作设备节点，普通 App 加载这个库多半会被 linker namespace 或 SELinux 拦住；`setWvMode` 在这台机器上是空实现，`onceUpdate / forceUpdate` 只是调它。**不建议使用**：收益小（前光有系统滑条），出错的后果难预料。
- `EinkManager` 还有 `setFullModeCnt`（局刷多少次后系统自动全刷，写的是 `persist.` 属性，会持久化，不能动）、`setContrast / setHanvonContrast`（对比度，汉王按应用设置会覆盖）、`standby / quitStandby`（屏幕待机）、`setRegalType`。这些都是全局设置，墨墨打不碰。
- `EPD_OVERLAY`（1，手写模式）是给笔迹低延迟绘制用的，键盘打字用不到。

### 没有发现的

- 没有按 View 或按区域指定刷新模式的接口：模式是全局的，候选窗、页眉页脚都跟着同一个模式。
- 没有刷新完成的回调。
