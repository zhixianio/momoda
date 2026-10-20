#!/usr/bin/env bash
# 下载内置字体（OFL 1.1）。字体体积大，不进 git。
# 校验 SHA-256：发布包里的字体必须和这里记录的一致（docs/RELEASE.md §7）。升级字体时同时改 VER 和 SHA256。
set -euo pipefail
cd "$(dirname "$0")/.."
VER=v1.530
SHA256=abd13364163e3cd84372b333f2fdcee9efb848634668f1c0ce5788dcd059ee61
DEST=app/src/main/assets/fonts
FILE="$DEST/LXGWWenKaiScreen.ttf"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DEST"
if [ ! -f "$FILE" ]; then
  curl -fSL -o "$FILE.part" \
    "https://github.com/lxgw/LxgwWenKai-Screen/releases/download/$VER/LXGWWenKaiScreen.ttf"
  mv "$FILE.part" "$FILE"
fi
actual=$(sha256 "$FILE")
if [ "$actual" != "$SHA256" ]; then
  echo "字体校验失败：$FILE" >&2
  echo "  期望 $SHA256" >&2
  echo "  实际 $actual" >&2
  echo "删除这个文件后重新运行；如果是有意升级字体，请更新脚本里的 VER 和 SHA256。" >&2
  exit 1
fi
echo "字体就绪：$FILE"
