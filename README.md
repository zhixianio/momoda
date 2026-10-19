# 墨墨打 momoda

为 Android 墨水屏设计、为中文优化的沉浸式写作应用。即开即打，全键盘操作。

> 当前阶段：**M0 验证原型**。设计见 [DESIGN.md](DESIGN.md)，验证进度见 [docs/M0.md](docs/M0.md)。

## 推荐输入法

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)（小企鹅输入法）：开源，实体键盘支持好，支持小鹤等双拼方案。

## 开发

```bash
source scripts/env.sh        # JDK 17 + Android SDK，只对当前 shell 生效
scripts/fetch-fonts.sh       # 下载内置字体（OFL 1.1，不进 git）
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

模拟器：`emulator -avd momoda_n10`（Android 14，1404×1872，带硬件键盘）。

卸载工具链：`scripts/uninstall-toolchain.sh`。

## 授权

字体：霞鹜文楷屏幕阅读版（LXGW WenKai Screen），SIL Open Font License 1.1，见 `app/src/main/assets/fonts/OFL.txt`。
