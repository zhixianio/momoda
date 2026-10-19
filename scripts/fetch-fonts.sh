#!/usr/bin/env bash
# 下载内置字体（OFL 1.1）。字体体积大，不进 git。
set -euo pipefail
cd "$(dirname "$0")/.."
VER=v1.530
DEST=app/src/main/assets/fonts
mkdir -p "$DEST"
[ -f "$DEST/LXGWWenKaiScreen.ttf" ] || curl -fSL -o "$DEST/LXGWWenKaiScreen.ttf" \
  "https://github.com/lxgw/LxgwWenKai-Screen/releases/download/$VER/LXGWWenKaiScreen.ttf"
echo "字体就绪：$DEST"
