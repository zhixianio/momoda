#!/usr/bin/env python3
"""从 Unicode Unihan 数据生成搜索用的拼音表：app/src/main/assets/pinyin.txt

用法：
    curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    unzip Unihan.zip Unihan_Readings.txt
    python3 scripts/gen-pinyin.py Unihan_Readings.txt

数据来源：Unihan 的读音字段，Unicode 授权条款见 https://www.unicode.org/terms_of_use.html 。
- 主读音：kMandarin（最常用的一个），用于排序。
- 其他读音：kTGHZ2013（通用规范汉字字典）和 kXHC1983（现代汉语词典），都没有时才用 kHanyuPinyin。
  多音字的每个读音都能搜到，例如“长文”用 cw 或 zw 都能找到。

输出格式：
    第一部分：每行一个无声调音节，后面是以它为主读音的所有字，例如 `zhang 长张章…`
    `#alt` 之后：同样格式，列出字的其他读音，例如 `chang 长…`
ü 写作 v（和大多数拼音输入法一致）。只收录基本区和扩展 A 区的汉字。
"""
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/pinyin.txt"
MAX_READINGS = 4


def plain(syllable: str) -> str:
    # 先分解，再把“u + 分音符”换成 v：带声调的 ǜ 是一个字符，直接替换 ü 会漏掉
    s = unicodedata.normalize("NFD", syllable).replace("ü", "v").replace("Ü", "v")
    s = "".join(c for c in s if not unicodedata.combining(c))
    return s.lower()


def syllables(field: str, value: str) -> list:
    if field == "kMandarin":
        raw = value.split()
    else:
        # 形如 `0121.040:cháng 1456.020:zhǎng` 或 `74468.170:zhǎng,cháng`
        raw = [s for part in value.split() for s in part.split(":", 1)[-1].split(",")]
    out = []
    for s in raw:
        p = plain(s)
        if re.fullmatch(r"[a-z]+", p) and p not in out:
            out.append(p)
    return out


def main(path: str) -> None:
    fields = defaultdict(dict)
    for line in open(path, encoding="utf-8"):
        if not line.startswith("U+"):
            continue
        code, field, value = line.rstrip("\n").split("\t", 2)
        if field not in ("kMandarin", "kTGHZ2013", "kXHC1983", "kHanyuPinyin"):
            continue
        cp = int(code[2:], 16)
        if 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF:
            fields[chr(cp)][field] = value

    primary = defaultdict(list)
    alternate = defaultdict(list)
    for ch, f in sorted(fields.items()):
        if "kMandarin" not in f:
            continue
        readings = syllables("kMandarin", f["kMandarin"])
        modern = syllables("kTGHZ2013", f.get("kTGHZ2013", "")) + syllables("kXHC1983", f.get("kXHC1983", ""))
        extra = modern or syllables("kHanyuPinyin", f.get("kHanyuPinyin", ""))
        for s in extra:
            if s not in readings:
                readings.append(s)
        readings = readings[:MAX_READINGS]
        if not readings:
            continue
        primary[readings[0]].append(ch)
        for s in readings[1:]:
            alternate[s].append(ch)

    def block(d):
        return [f"{s} {''.join(cs)}" for s, cs in sorted(d.items())]

    header = "# 由 scripts/gen-pinyin.py 从 Unicode Unihan 生成，见 https://www.unicode.org/terms_of_use.html\n"
    OUT.write_text(header + "\n".join(block(primary)) + "\n#alt\n" + "\n".join(block(alternate)) + "\n", encoding="utf-8")
    n_alt = sum(len(c) for c in alternate.values())
    print(f"{sum(len(c) for c in primary.values())} 个字，其中多音读音 {n_alt} 条 → {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
