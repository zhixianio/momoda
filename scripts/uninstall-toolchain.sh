#!/usr/bin/env bash
# 卸载 momoda 开发所装的 Android 工具链。逐项确认，不会静默删除。
set -u

confirm() { read -r -p "$1 [y/N] " a; [[ "$a" == [yY] ]]; }

if command -v adb >/dev/null 2>&1 || [ -x /opt/homebrew/share/android-commandlinetools/platform-tools/adb ]; then
  /opt/homebrew/share/android-commandlinetools/platform-tools/adb emu kill 2>/dev/null || true
fi

confirm "卸载 Android 命令行工具及 SDK（/opt/homebrew/share/android-commandlinetools，含模拟器镜像）？" && {
  brew uninstall --cask android-commandlinetools
  rm -rf /opt/homebrew/share/android-commandlinetools
}

# 注意：Homebrew 的 openjdk（最新版）在安装本工具链之前就已存在，这里不动它。
confirm "卸载 Gradle（仅用于生成 wrapper）？" && brew uninstall gradle
confirm "卸载 MTP 命令行工具（android-file-transfer-linux）？" && brew uninstall android-file-transfer-linux
confirm "卸载 JDK 17（openjdk@17）？" && brew uninstall openjdk@17
confirm "清理 Homebrew 中不再被依赖的包（brew autoremove，会先列出）？" && brew autoremove

echo "⚠️  下面两个目录可能被其他 Android / Gradle 项目共用，确认没有其他项目再删。"
confirm "删除 ~/.android（模拟器虚拟机、adb 密钥）？" && rm -rf ~/.android
confirm "删除 ~/.gradle（Gradle 依赖缓存）？" && rm -rf ~/.gradle

echo "完成。项目内的 build/ 目录可在仓库里直接删除。"
