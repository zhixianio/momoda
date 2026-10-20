# 第三方授权清单

墨墨打本身以 GNU General Public License v3.0 发布（见 [LICENSE](LICENSE)）。下面列出随 APK 分发、或仓库里包含的第三方内容。

## 随 APK 分发

| 内容 | 位置 | 授权 | 义务 |
|---|---|---|---|
| 霞鹜文楷屏幕阅读版 LXGW WenKai Screen v1.530 | `assets/fonts/LXGWWenKaiScreen.ttf`（构建时由 `scripts/fetch-fonts.sh` 下载） | SIL Open Font License 1.1 | 随包附带授权全文（`assets/fonts/OFL.txt`）；字体不能单独出售；**保留字体名**“霞鹜”“LXGW”等：如果对字体做子集化或修改后再打包，修改版不能沿用这些名字（作者的额外许可只覆盖网页字体用途） |
| 拼音表（由 Unicode Unihan 数据库 `kMandarin`、`kTGHZ2013`、`kXHC1983`、`kHanyuPinyin` 字段生成） | `assets/pinyin.txt` | Unicode License v3 | 随包附带版权和许可声明（`assets/licenses/UNICODE-LICENSE.txt`），或写在相关文档里 |
| Kotlin 标准库 `org.jetbrains.kotlin:kotlin-stdlib:2.1.0` | 编译进 `classes.dex` | Apache License 2.0 | 在“关于 · 开源许可”里给出授权全文和版权声明 |
| JetBrains annotations `org.jetbrains:annotations:13.0` | 仅注解，R8 会移除 | Apache License 2.0 | 同上（一般编译后不留痕迹，列出以防万一） |

## 只在仓库或构建过程里

| 内容 | 授权 | 说明 |
|---|---|---|
| Gradle Wrapper（`gradle/wrapper/gradle-wrapper.jar`） | Apache License 2.0 | 不进 APK |
| JUnit 4.13.2 / Hamcrest | EPL 1.0 / BSD-3-Clause | 只用于单测，不进 APK |
| org.json 20240303 | Public Domain | 只用于单测（JVM 上替代 Android 自带的 org.json），不进 APK |
| Android Gradle Plugin、Android SDK | Apache License 2.0 / Android SDK 许可协议 | 构建工具，不进 APK |

## 只推荐、不分发

| 内容 | 授权 | 说明 |
|---|---|---|
| fcitx5-android（小企鹅输入法） | LGPL-2.1-or-later（部分组件为其他授权） | README 里推荐用户自行安装。墨墨打不包含、不链接它的任何代码，没有授权义务。如果以后真的“内嵌输入法引擎”（DESIGN.md 里评估过），就要重新评估 LGPL 的义务 |

## 系统组件

ICU（`android.icu.*`）、系统字体等由 Android 系统提供，不随 APK 分发。
