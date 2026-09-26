#!/usr/bin/env python3
"""核对各语言译文与默认词条（values/strings.xml，简体中文）是否对得上。

- 每个在 app/src/main/res/xml/locales_config.xml 登记的语言（默认的 zh-Hans 除外），
  凡是有默认 strings.xml 的模块都必须有一份对应的 values-<限定符>/strings.xml；
- 译文不缺词条、不多词条（translatable="false" 的不该出现在译文里）；
- 占位符与默认词条一致（位置与类型）；plurals 按各档的并集比较，允许某一档不带数字
  （如英文 one 档写成「the only course」）；
- plurals 补齐该语言需要的数量档（见 REQUIRED_QUANTITIES）；string-array 条目数一致；
- 文件里没有换行、回车、制表符以外的控制字符（写文件时转义序列被落成真字符的老坑）。

用法：python tools/check_translations.py      退出码 1 表示有问题。
"""
from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
DEFAULT_TAG = "zh-Hans"
LOCALES_CONFIG = "app/src/main/res/xml/locales_config.xml"

# 各语言 plurals 必须写出的数量档（CLDR 基数规则）。没列出的语言只要求 other。
REQUIRED_QUANTITIES: dict[str, set[str]] = {
    "en": {"one", "other"},
}

PLACEHOLDER = re.compile("%(?:([0-9]+)[$])?[-#+ 0,(]*[0-9]*(?:[.][0-9]+)?([a-zA-Z])")
ALLOWED_CONTROL = {chr(9), chr(10), chr(13)}


def placeholders(text: str) -> set[tuple[int, str]]:
    text = text.replace("%%", "")
    found = set()
    for order, match in enumerate(PLACEHOLDER.finditer(text), start=1):
        index = int(match.group(1)) if match.group(1) else order
        found.add((index, match.group(2)))
    return found


def text_of(element: ET.Element) -> str:
    return "".join(element.itertext())


def load(path: str) -> dict[tuple[str, str], object]:
    """(种类, 名称) → string 为占位符集合；plurals 为 {档: 占位符}；string-array 为条目数。"""
    entries: dict[tuple[str, str], object] = {}
    for element in ET.parse(path).getroot():
        name = element.get("name")
        if name is None or element.get("translatable") == "false":
            continue
        if element.tag == "string":
            entries[("string", name)] = placeholders(text_of(element))
        elif element.tag == "plurals":
            entries[("plurals", name)] = {
                item.get("quantity"): placeholders(text_of(item)) for item in element
            }
        elif element.tag == "string-array":
            entries[("string-array", name)] = len(list(element))
    return entries


def untranslatable(path: str) -> set[str]:
    return {
        element.get("name")
        for element in ET.parse(path).getroot()
        if element.get("translatable") == "false"
    }


def qualifier(tag: str) -> str:
    parts = tag.split("-")
    return parts[0] if len(parts) == 1 else "b+" + "+".join(parts)


def control_chars(path: str) -> list[int]:
    with open(path, encoding="utf-8") as f:
        lines = f.read().split(chr(10))
    return [
        number
        for number, line in enumerate(lines, start=1)
        if any(ord(ch) < 32 and ch not in ALLOWED_CONTROL for ch in line)
    ]


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)
    problems: list[str] = []

    tags = [
        element.get(ANDROID_NS + "name")
        for element in ET.parse(LOCALES_CONFIG).getroot()
    ]
    languages = [tag for tag in tags if tag != DEFAULT_TAG]

    defaults = sorted(
        path.replace(os.sep, "/")
        for path in glob.glob("**/src/main/res/values/strings.xml", recursive=True)
        if "/build/" not in path.replace(os.sep, "/") and not path.startswith(".")
    )

    checked = 0
    for default_path in defaults:
        base = load(default_path)
        fixed = untranslatable(default_path)
        res_dir = os.path.dirname(os.path.dirname(default_path))
        for tag in languages:
            path = f"{res_dir}/values-{qualifier(tag)}/strings.xml"
            if not os.path.exists(path):
                problems.append(f"{path}: 缺少译文文件（{tag} 已在 {LOCALES_CONFIG} 登记）")
                continue
            checked += 1
            for number in control_chars(path):
                problems.append(f"{path}:{number}: 含控制字符")
            try:
                translated = load(path)
            except ET.ParseError as e:
                problems.append(f"{path}: XML 解析失败：{e}")
                continue
            required = REQUIRED_QUANTITIES.get(tag.split("-")[0], {"other"})
            for name in sorted(fixed & {key[1] for key in translated}):
                problems.append(f"{path}: {name} 标了 translatable=false，不应出现在译文里")
            for key in sorted(base.keys() - translated.keys()):
                problems.append(f"{path}: 缺少 {key[0]} {key[1]}")
            for key in sorted(translated.keys() - base.keys()):
                if key[1] not in fixed:
                    problems.append(f"{path}: 多出 {key[0]} {key[1]}（默认词条里没有）")
            for key in sorted(base.keys() & translated.keys()):
                kind, name = key
                expected, actual = base[key], translated[key]
                if kind == "string" and expected != actual:
                    problems.append(f"{path}: {name} 占位符不一致：默认 {sorted(expected)}，译文 {sorted(actual)}")
                elif kind == "plurals":
                    union_expected = set().union(*expected.values())
                    union_actual = set().union(*actual.values())
                    if union_expected != union_actual:
                        problems.append(
                            f"{path}: {name} 占位符不一致：默认 {sorted(union_expected)}，译文 {sorted(union_actual)}"
                        )
                    missing = required - actual.keys()
                    if missing:
                        problems.append(f"{path}: {name} 缺少数量档 {sorted(missing)}")
                elif kind == "string-array" and expected != actual:
                    problems.append(f"{path}: {name} 条目数不一致：默认 {expected}，译文 {actual}")

    if problems:
        print(chr(10).join(problems))
        print(f"失败：{len(problems)} 处问题。")
        return 1
    print(f"通过：{len(languages)} 种译文语言，{checked} 份译文文件与默认词条一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
