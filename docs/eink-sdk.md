# 墨水屏厂商接口调研

2026-10-22。目标：让打字更跟手、残影更少，并找出各品牌系统里其他能利用的能力。

标记：**【已验证】** 在真机上用墨墨打（普通第三方 App，targetSdk 34）实测过；**【已核对】** 读过设备上的系统框架代码，没有实际调用；**【资料】** 只来自文档、源码仓库或论坛，未在真机上验证。

代码在 `app/src/main/java/app/momoda/Eink.kt`：`EinkController` 接口，`RockchipEink` 一个实现，识别不出来的设备用 `NoEink`（什么都不做）。

## 汉王 N10 Pro（Rockchip RK3576，Android 14，固件 EBook-2.2.6）

### 刷新模式：可用【已验证】

- `context.getSystemService("eink")` 返回 `android.os.EinkManager`（Rockchip 电子书 SDK 的类，不在公开 SDK 里）。反射调用 `setMode(String)`、`getMode()`、`sendOneFullFrame()` 都能用，Android 14 的隐藏接口限制没有拦它，也不需要任何权限。
- 系统服务端 `EinkService.setProperty` 不检查调用者，直接 `SystemProperties.set`。`setMode` 写的是 `sys.eink.mode`：`sys.` 开头，**不持久化，重启复位**。
- 模式编号：0 AUTO、1 OVERLAY（手写）、2 FULL_GC16（全刷）、3–6 FULL_GL16 等、7 PART_GC16（普通局刷）、8–11 PART_GL16 等、12 A2、13 DU。
- **编号的坑**：框架里 `EinkManager.EinkMode` 的常量是 Rockchip 内核 4.19 分支的编号（A2_DITHER=13、DU=14、DU4=15、A2_ENTER=16、RESET=17、AUTO_DU=22），但 N10 Pro 跑的是 6.1.75 内核，6.1 分支里 DU=13、RESET=14。汉王自己的“极速”写的就是 13，Rockchip 文档也写 13=DU。所以**不能用反射读这些常量**。墨墨打的做法是：DU 按内核版本取值（6.1 用 13，4.19 用 14），其他版本的内核不提供“极速”；AUTO=0 和 A2=12 在两个分支里都一样。
- `setMode` 的副作用：除模式 15 外，没开相机时会把 `persist.ebook.bw_repair_en` 设成 1（这台机器上本来就是 1）。
- **汉王的“按应用刷新设置”会覆盖我们**：应用回到前台约 100ms 后，SystemUI 的 `EinkSettingsProvider` 按该应用在汉王设置里的刷新模式再设一次（日志：`packageName=app.momoda … refreshMode=13 … bNeedSetRefreshMode=true`），并发出非保护广播 `com.rockchip.eink.appcustom`。用户说的“极速”就是这里给墨墨打设的 13（DU）；桌面是 9。
  - 对策：`onResume`、拿回窗口焦点、**每次按键**都确认一次。按键时直接读系统属性 `sys.eink.mode`（不走 binder，微秒级），和目标不同才切。
- 恢复：`onPause` 时改回进入前的模式；只在屏幕还是我们设的模式时才改，用户或系统在中途改过就不动。进入前的模式存在 SharedPreferences（commit），进程被杀后下次启动还认得；崩溃处理器里也会恢复。即使墨墨打意外退出没来得及恢复，汉王切到下一个应用时 SystemUI 也会按那个应用的设置重设。

实测（adb 读 `sys.eink.mode`）：桌面 9 → 打开墨墨打先被设成 0 → SystemUI 改成 13 → 第一次按键改回 0 → 按 Home 时墨墨打改回 13 → 桌面的 SystemUI 改成 9。设置里切“极速 / 黑白 / 跟随系统 / 自动”分别得到 13 / 12 / 13（恢复）/ 0。

### 用户实测结论（2026-10-22）

- **默认“跟随系统”**：墨墨打不改刷新模式，用汉王设置里给墨墨打设的模式（用户设的是“极速”，13）。
- **“自动”（0）不适合**：打字时候选窗等灰色区域黑白闪烁，比“极速”严重得多。
- **停笔清残影默认关**：10 秒一次的全刷会闪一下屏幕，明显打断写作。
- 设置里的“打字时刷新”“停笔后清残影”和 Ctrl+Shift+E 手动全刷都保留，需要时自己打开。
- 原因推测（**未验证**）：AUTO 按像素选波形，灰色像素走 GC16，候选窗的灰底、灰字每次更新都会闪；DU 只有黑白两级波形，所以不闪。

### 各模式对比

| 模式 | 编号 | 官方说明 | 打字时的预期 |
|---|---|---|---|
| 自动 AUTO | 0 | 为键盘这类要求快速响应的场景设计；按像素在 GC16 和 DU 之间选，多区域同时连续刷新，标称最快，16 级灰 | 文档说连续打字时多个区域可以并行刷；实测候选窗等灰色区域闪烁严重，不作默认 |
| 极速 DU | 13（4.19 内核是 14） | 只刷变化的像素，黑白两级波形，快 | 汉王“极速”，用户实际在用的模式。抗锯齿的灰边被压成黑白，残影会累积 |
| 黑白 A2 | 12 | 最快的黑白波形，带抖动；进入前系统先用 DU 刷一次 | 最快，但字边缘有锯齿和抖动纹，灰色的页眉页脚也会变成网点 |
| 普通局刷 PART_GC16 | 7 | 16 级灰，无闪烁 | 最清楚、最慢，不提供给打字 |

**手感和残影要人眼看**：驱动的刷新耗时不对 App 开放（`/sys/devices/platform/ebc-dev/*` 都是 Permission denied，也没有相关日志），软件只能量到“按键到交给屏幕”这一段（Ctrl+Shift+L）。在设置“打字时刷新”里可以随时切换比较。

### 全刷清残影：可用【已验证】，默认关

- `sendOneFullFrame()` 只是把进程内的计数写进 `sys.ebook.one_full_mode_timeline`，显示服务看到值变了，就把**下一帧**用 GC16 全刷；所以调用后要让窗口重画一帧（`rootView.invalidate()`）。
- 计数每个进程从 1 开始，可能刚好等于属性里其他 App 留下的值（汉王切换应用时也会调它，实测值 140、144…）：写完值没变就再调一次。
- 不改变当前模式，不需要“切到 GC16 刷一帧再切回”。
- 墨墨打的用法（打开“停笔后清残影”时）：停笔 5 / 10 / 30 / 60 秒后全刷一次。改动少于 3 个字不刷；输入法有未上屏的拼音（编辑器里有 composing 区域）时不刷；每次按键（包括拼写中、文字没变的按键）都重新计时；有弹窗时不刷。翻页、连续模式下的滚动算一大块改动；换文档时如果有该清的残影就立刻全刷，和整屏重画合成一次。Ctrl+Shift+E 随时手动全刷。
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

## 其他品牌【资料】

都没有在真机上验证。来源主要有三类：厂商仓库；KOReader 的 Android 启动器（`koreader/android-luajit-launcher` 里的 `device/` 目录，作者从固件里还原出调用方式并在真机上测过，下文标“KOReader”）；媒体报道和论坛。

| 品牌 | 芯片 | 刷新控制 | 全刷 | 结论 |
|---|---|---|---|---|
| 文石 Boox | 新机型高通，老机型 RK3026 | 官方 SDK `onyxsdk-device`（`EpdController`、`Device.currentDevice()`） | `EpdController.repaintEveryThing(UpdateMode.GC)` | 可以直接用，但要引入 AAR |
| Meebook M6 / M6C / M7 | RK3566 | 和汉王一样，都是 `android.os.EinkManager`（KOReader） | `sendOneFullFrame()` | 现有实现大概率能用，DU 要用 14 |
| Boyue / Likebook | RK3026 / RK3368 | 老 Rockchip 接口：`View.requestEpdMode`、`View.setByEinkUpdateMode`（KOReader） | 同左 | 需要反射，没有验证 |
| 全志（Moaan W7 等，sun8iw15p1） | 全志 | 反射 `ViewRootImpl.setRefreshMode(int)`（KOReader） | 写 GC16=0x04 | 需要反射。模式设置会一直保留，KOReader 设置 150ms 后写回 0 |
| 联想 Smart Paper | RK3566 | `EinkManager` | `sendOneFullFrame()` 无效，要用 `screenRefresh(true, 1)`（KOReader） | 同名类，方法不同 |
| 科大讯飞 AiNote X1 | RK3288 | `View.setEinkUpdateMode(short, short)`（KOReader） | — | 需要反射 |
| 海信 A9 / A10 / Touch | 骁龙 662 / QCM6690 | 没有找到 | 没有找到 | 没有公开接口 |
| Bigme | 联发科 P35 / Dimensity | xRapid、xClear 只在系统设置里（E-Ink Center 可以按应用设置），没有 SDK | 没有找到 | 没有公开接口 |
| 掌阅 iReader | 早期 i.MX6，新机型不详 | 没有找到 | 没有找到 | 没有公开接口 |

### 文石 Boox 的细节

- 依赖：`com.onyx.android.sdk:onyxsdk-device`（官方 Demo 用的是 1.3.5.3），另有 `onyxsdk-pen`、`onyxsdk-base`。仓库地址 `http://repo.boox.com/repository/maven-public/`。Demo 代码是 Apache-2.0，AAR 本身的许可没有找到说明，引入前要确认能不能和 GPL-3.0 一起分发。
- 刷新模式：可以按 View 设（`EpdController.setViewDefaultUpdateMode(view, UpdateMode.X)`、`EpdController.invalidate(view, UpdateMode.GC)`），也可以按应用设（`Device.currentDevice().setAppScopeRefreshMode(UpdateOption.FAST / REGAL …)`，或 `applyAppScopeUpdate` / `clearAppScopeUpdate`）。UpdateMode 有 DU、GU、GC、REGAL、ANIMATION 等。
- 不引入 AAR 也能反射调用（KOReader 的做法）：`View.setWaveformAndScheme(5, 1, 0)`，以及 `View.refreshScreen(x, y, w, h, mode)`，mode 取 DU=1、GC16=2、REGAL=6，可以再加 FULL=32、WAIT=64。前光用 `android.onyx.hardware.DeviceController.setLightValue(type, value)`。
- 其他能力：
  - 笔的低延迟绘制：`TouchHelper.create(view, callback).openRawDrawing()`。
  - 前光冷暖：`BrightnessController`，需要 WRITE_SETTINGS 权限。
- 识别：`Build.MANUFACTURER` 或 `BRAND` 转成小写后等于 `onyx`。
- 系统的“按应用优化”（E-Ink Center）和 SDK 调用哪个优先，没有找到说明。

### 识别方式

- Rockchip 电子书固件：`persist.sys.rk-ebook=true` 或 `ro.rk.ebook_settings=true`，并且 `getSystemService("eink")` 不为空。墨墨打现在只认这个。
- Meebook：`MANUFACTURER=haoqing`，`MODEL` 为 m6、m6c、m7。
- Boox：`MANUFACTURER` 或 `BRAND` 为 onyx。
- 全志：`BRAND=allwinner`，`HARDWARE=sun8iw15p1`。

### 翻页键

所有品牌都没有找到可靠的键值资料。等有设备时用墨墨打的调试面板（Ctrl+Shift+D）看按键日志。

## 下一步建议

1. Meebook M7 等 RK3566 设备现有代码就能覆盖，有机会借一台测一下，DU 用的是 14。
2. 文石 Boox 用户多。KOReader 的反射路径可以不引入 AAR，许可上没有顾虑；可以做成第二个 `EinkController` 实现。
3. 全志设备上模式会一直保留，必须在离开前写回，和这里的 `ModeKeeper` 思路一致。

## 来源

- Rockchip 电子书 SDK 文档（EPD 模式说明和示例代码）。
- Rockchip 内核 `ebc_dev.h`：https://github.com/rockchip-linux/kernel/blob/develop-4.19/drivers/gpu/drm/rockchip/ebc-dev/ebc_dev.h 、https://github.com/rockchip-linux/kernel/blob/develop-6.1/drivers/gpu/drm/rockchip/ebc-dev/ebc_dev.h
- 文石官方 Demo：https://github.com/onyx-intl/OnyxAndroidDemo
- KOReader 启动器：https://github.com/koreader/android-luajit-launcher （`app/src/main/java/org/koreader/launcher/device/`）
- 海信、Bigme 的芯片：Liliputing、IT之家的报道。
- 汉王部分：设备上的 `/system/framework/framework.jar`、`services.jar`（dexdump），以及 adb 实测。
