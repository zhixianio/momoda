#!/usr/bin/env python3
"""从 Unicode Unihan 数据生成搜索用的拼音表：app/src/main/assets/pinyin.txt

用法：
    curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    unzip Unihan.zip Unihan_Readings.txt
    python3 scripts/gen-pinyin.py Unihan_Readings.txt

数据来源：Unihan 的 kMandarin 字段（取第一个、也就是最常用的读音），
Unicode 授权条款见 https://www.unicode.org/terms_of_use.html 。
输出格式：每行一个无声调音节，后面跟着所有读这个音的字，例如 `zhong 中种重众…`。
ü 写作 v（和大多数拼音输入法一致）。只收录基本区和扩展 A 区的汉字。
"""
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/pinyin.txt"


def plain(syllable: str) -> str:
    # 先分解，再把“u + 分音符”换成 v：带声调的 ǜ 是一个字符，直接替换 ü 会漏掉
    s = unicodedata.normalize("NFD", syllable).replace("u\u0308", "v").replace("U\u0308", "v")
    s = "".join(c for c in s if not unicodedata.combining(c))
    return s.lower()


def main(path: str) -> None:
    by_syllable = defaultdict(list)
    for line in open(path, encoding="utf-8"):
        if not line.startswith("U+"):
            continue
        code, field, value = line.rstrip("\n").split("\t", 2)
        if field != "kMandarin":
            continue
        cp = int(code[2:], 16)
        if not (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF):
            continue
        syllable = plain(value.split()[0])
        if syllable.isascii() and syllable.isalpha():
            by_syllable[syllable].append(chr(cp))

    lines = [f"{s} {''.join(chars)}" for s, chars in sorted(by_syllable.items())]
    header = "# 由 scripts/gen-pinyin.py 从 Unicode Unihan（kMandarin）生成，见 https://www.unicode.org/terms_of_use.html\n"
    OUT.write_text(header + "\n".join(lines) + "\n", encoding="utf-8")
    total = sum(len(c) for c in by_syllable.values())
    print(f"{len(by_syllable)} 个音节，{total} 个字 → {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1])
