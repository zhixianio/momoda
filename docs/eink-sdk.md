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
- **不自动清残影**：试过停笔 10 秒自动全刷，GC16 会让整屏闪一下，明显打断写作。
- **全屏灰阶清残影没有效果**：GL16、GLR16、GLD16 三种都试过，用户看不出能清掉残影（见下文“更安静的清残影”）。
- 最后保留的是：设置里的“打字时刷新”（跟随系统 / 自动 / 极速 / 黑白，默认跟随系统），以及 Ctrl+Shift+E 或命令面板“清除残影”手动全刷（GC16）。停笔自动清、清残影方式、翻页和换文档时清、“灰阶局刷”都已删掉。
- 原因推测（**未验证**）：AUTO 按像素选波形，灰色像素走 GC16，候选窗的灰底、灰字每次更新都会闪；DU 只有黑白两级波形，所以不闪。

### 各模式对比

| 模式 | 编号 | 官方说明 | 打字时的预期 |
|---|---|---|---|
| 自动 AUTO | 0 | 为键盘这类要求快速响应的场景设计；按像素在 GC16 和 DU 之间选，多区域同时连续刷新，标称最快，16 级灰 | 文档说连续打字时多个区域可以并行刷；实测候选窗等灰色区域闪烁严重，不作默认 |
| 极速 DU | 13（4.19 内核是 14） | 只刷变化的像素，黑白两级波形，快 | 汉王“极速”，用户实际在用的模式。抗锯齿的灰边被压成黑白，残影会累积 |
| 黑白 A2 | 12 | 最快的黑白波形，带抖动；进入前系统先用 DU 刷一次 | 最快，但字边缘有锯齿和抖动纹，灰色的页眉页脚也会变成网点 |
| 普通局刷 PART_GC16 | 7 | 16 级灰，无闪烁 | 最清楚、最慢，不提供给打字 |

**手感和残影要人眼看**：驱动的刷新耗时不对 App 开放（`/sys/devices/platform/ebc-dev/*` 都是 Permission denied，也没有相关日志），软件只能量到“按键到交给屏幕”这一段（Ctrl+Shift+L）。在设置“打字时刷新”里可以随时切换比较。

### 手动全刷清残影：可用【已验证】

- `sendOneFullFrame()` 只是把进程内的计数写进 `sys.ebook.one_full_mode_timeline`，显示服务看到值变了，就把**下一帧**用 GC16 全刷；所以调用后要让窗口重画一帧（`rootView.invalidate()`）。
- 计数每个进程从 1 开始，可能刚好等于属性里其他 App 留下的值（汉王切换应用时也会调它，实测值 140、144…）：写完值没变就再调一次。
- 不改变当前模式，不需要“切到 GC16 刷一帧再切回”。
- 墨墨打的用法：只在用户按 Ctrl+Shift+E 或在命令面板选“清除残影”时全刷。
- 试过、已删掉的做法：停笔 N 秒后自动全刷。拼写中、刚按过键、有弹窗时不刷；换文档时顺便全刷。实现没有问题，但自动全刷的闪屏打断写作。

### 更安静的清残影：全屏灰阶波形【已验证：没有效果，已删掉】

用户觉得 GC16 全刷整屏黑白翻转一次会打断写作，所以试了别的波形。做法是：先 `setMode` 换成全屏灰阶模式，再让窗口重画一帧，画完 300ms 后换回进入前的模式。显示服务提交每一帧时，都会把当时的模式写进 `ebc_buf_info.epd_mode` 交给驱动。

| 方式 | 编号 | 按波形定义的预期（用户实测见表下） |
|---|---|---|
| 全刷 GC16（`sendOneFullFrame`） | — | 每个像素都先翻转再回来，会闪，清得最干净 |
| 全屏 GL16 | 3 | 针对白底黑字，白色像素不动，基本不闪；清残影的效果不如 GC16 |
| 全屏 GLR16（REGAL） | 4 | GL16 加上去残影的算法，专门为少闪、少残影设计 |
| 全屏 GLD16 | 5 | REGAL 的另一种变体 |
| 局刷 PART_GL16 | 8 | 只刷变化的像素。整屏重画但内容没变时什么都不刷，**不能用来清残影**；曾作为打字模式“灰阶局刷”提供，用来和极速对比，已删掉 |

实测（adb 读模式、看日志的时间戳）：
- 设置里换清残影方式、Ctrl+Shift+E、翻页（PageUp / PageDown）、换文档、关掉面板，都是 13 → 3，约 320ms 后 3 → 13。
- 翻页和换文档时，模式在 `scrollTo` / 载入文档之后、这一帧画出来之前换好，所以和本来的整屏重画合成一次刷新。换文档时会触发两次（换文档本身和随后滚到光标处），但临时模式只切一次、恢复一次。
- 关掉面板要等 200ms，等面板窗口移走再清；否则清的那一帧里面板还在，移走面板的那次刷新又是快速波形。
- 清的过程中离开墨墨打：`onPause` 立刻换回 13。清的过程中按键：先不“纠正”模式，等清完换回。
- 汉王 SystemUI 只在应用回到前台时改模式，清的过程中没有出现过系统抢着改回的情况。
- **用户实测结论**：只有 GC16 全刷能看出效果；GL16、GLR16、GLD16 都看不出清掉了残影。所以这几种方式，连同“翻页、换文档时清残影”和打字模式“灰阶局刷”，都删掉了。
- 猜测（**未验证**）：灰阶波形只驱动灰阶有变化的像素，内容没变的像素基本不动，DU 留下的残影也就留在那里。

### 其他能力【已核对，未调用】

框架里汉王自己的类（`android.hwebook.*`）：

- `HvEinkManager.setEinkMode / getEinkMode`：只是 `EinkManager.setMode / getMode` 的包装，没有额外价值。
- `HanvonEbk`：`setColdBrightness / setWarmBrightness / setCwlBrightness`（前光冷暖）、`disableKeys`（屏蔽按键）、`enterHwMode`（手写模式）、`setSystemSleepMode`、`onceUpdate / forceUpdate` 等。几乎全是 JNI（`libHANVONEBK_jni.so`）直接操作设备节点，普通 App 加载这个库多半会被 linker namespace 或 SELinux 拦住；`setWvMode` 在这台机器上是空实现，`onceUpdate / forceUpdate` 只是调它。**不建议使用**：收益小（前光有系统滑条），出错的后果难预料。
- `EinkManager` 还有 `setFullModeCnt`（局刷多少次后系统自动全刷，写的是 `persist.` 属性，会持久化，不能动）、`setContrast / setHanvonContrast`（对比度，汉王按应用设置会覆盖）、`standby / quitStandby`（屏幕待机）、`setRegalType`。这些都是全局设置，墨墨打不碰。
- `EPD_OVERLAY`（1，手写模式）是给笔迹低延迟绘制用的，键盘打字用不到。

### 没有发现的

- 没有按 View 或按区域指定刷新模式的接口：模式是全局的，候选窗、页眉页脚都跟着同一个模式。
- 没有刷新完成的回调。

## 汉王 Clear 7 Turbo（型号 P7，高通 bengal，Android 14，内核 5.15）

### 结论：只有极速可用，墨墨打不做适配、跟随系统【用户实测】

用户在 Clear 7 Turbo 上实测，只有“极速”显示正常，其他刷新模式都不行。所以墨墨打在这台机器上不做专门适配，直接跟随系统的刷新设置；想要极速，就在汉王的“显示优化”里给墨墨打选极速。下面是当时调查和接口实测的记录，留作参考。

### 刷新模式：可用【已验证】

- 也有 `android.os.EinkManager`，汉王把 Rockchip 那套框架移植了过来。`setMode(String)`、`getMode()`、`sendOneFullFrame()` 的签名都一样，服务端 `EinkService.setProperty` 同样不检查调用者，反射调用没有被拦。
- 没有 `persist.sys.rk-ebook` 这类属性。当时用 `Build.BRAND=HANVON` 且 `Build.HARDWARE=qcom` 识别（`MANUFACTURER` 是 QUALCOMM，不能用来认）；适配撤掉后墨墨打不再识别这台机器。
- 全刷写的属性名不一样：这里是 `sys.eink.one_full_mode_timeline`，N10 是 `sys.ebook.…`。
- **编号是另一套**，`setMode` 里会做转换：
  - 写 7→8、9→26、14→28、15→31、13 或 16→32；
  - 打开夜间模式（反色）时再换成夜间版：26→27、30→31、32→33、34→35。
  - 所以写下去的编号和读回来的可能不同。当时的适配让 `ModeKeeper` 记写完以后读回来的值，否则每次按键都会以为模式被别人改了（已随适配一起撤掉）。
- 汉王“显示优化”里按应用设置的刷新（SystemUI 插件 `DisplayOptimizeSettings.RefreshMode`，括号里是日间 / 夜间）：普通 8、REGAL 26/27、MEDIUM 28/29、FAST 30/31、RAPIDLY（极速）32/33。没有单独设置的应用用 26，墨墨打现在就是 26。
- 墨墨打写的编号：自动 0、极速 13（和 N10 一样写汉王“极速”的老编号，读回来是 32）、黑白 12。0 和 12 系统都照原样接受了。汉王自带输入法在点软键盘时也会写 0 或 15，所以 0 在这台上是有意义的模式。12 不在汉王的选项里，能不能用要看屏幕。
- 和 N10 一样，应用回到前台约 150ms 后，SystemUI 会按应用设置再写一次模式。墨墨打拿回窗口焦点时、每次按键时会再确认一遍。实测：切到极速后打字是 32；按 Home 恢复成 26；回来后 SystemUI 先改成 26，随即被墨墨打改回 32。
- 手动全刷：Ctrl+Shift+E 每按一次，`sys.eink.one_full_mode_timeline` 就变一次（实测 7 → 2 → 3）。
- 汉王输入法（`com.hanvon.inputmethod.hanvonime.pad`）点软键盘时会自己切模式（P7 上写 0 或 15），软键盘收起时再写回之前的模式。用实体键盘打字时不会走这条路；用软键盘时它和墨墨打的“打字时刷新”可能互相覆盖。
- 测试中的一个坑：这台机器上，adb 发 Esc 关不掉设置面板，墨墨打的面板要点外面才关得掉。实体键盘上的 Esc 没有试过。

### 要人眼确认的

- 自动（0）、极速（32）、黑白（12）在这块屏上闪不闪、跟手不跟手，尤其黑白 12 是否真的生效。
- 手动全刷时，屏幕是否闪了一下、残影是否清掉。

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

- Rockchip 电子书固件：`persist.sys.rk-ebook=true` 或 `ro.rk.ebook_settings=true`，并且 `getSystemService("eink")` 不为空。
- 墨墨打只认这一种。其他设备即使有名为 eink 的服务，也不去调用。
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
