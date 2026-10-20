#!/usr/bin/env python3
"""把霞鹜文楷屏幕阅读版子集化为网页字体：只保留站内 HTML 和 JS 用到的字符。

用法（需要 fonttools 和 brotli：pip install fonttools brotli）：
    python3 tools/build-font.py /path/to/LXGWWenKaiScreen.ttf

字体来自 https://github.com/lxgw/LxgwWenKai-Screen （SIL OFL 1.1，见 fonts/OFL.txt）。
作者的额外许可允许为网页字体用途子集化并沿用原字体名。改了 HTML 文字后要重新运行。
"""
import re
import sys
from html import unescape
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "fonts" / "wenkai-screen-subset.woff2"

chars = set(chr(c) for c in range(0x20, 0x7F))  # 基本拉丁字母和符号始终保留
chars |= set("，。、；：？！“”‘’（）《》〈〉【】—…·～　├└─│→")
for html in ROOT.glob("*.html"):
    chars |= set(unescape(re.sub(r"<[^>]+>", " ", html.read_text(encoding="utf-8"))))
for js in ROOT.glob("*.js"):  # 打字演示的文字在 site.js 里，原样全部收进来
    chars |= set(js.read_text(encoding="utf-8"))
chars = {c for c in chars if c.isprintable() or c == "　"}

options = subset.Options()
options.flavor = "woff2"
options.layout_features = ["*"]   # 保留 kern、fwid 等排版特性
options.name_IDs = ["*"]          # 保留版权和授权信息
options.notdef_outline = True
font = subset.load_font(sys.argv[1], options)
subsetter = subset.Subsetter(options)
subsetter.populate(text="".join(sorted(chars)))
subsetter.subset(font)
subset.save_font(font, str(OUT), options)
print(f"{len(chars)} 个字符 → {OUT.relative_to(ROOT)}（{OUT.stat().st_size // 1024} KB）")
