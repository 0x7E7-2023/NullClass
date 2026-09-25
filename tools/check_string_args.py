#!/usr/bin/env python3
"""核对取词调用传入的参数个数与词条占位符是否一致。

`UiText.Res(R.string.x, a, b)` 这类间接取词，Lint 的 StringFormatMatches 看不见：
参数少了，界面上会露出「%1$s」；多了，信息被静默丢掉；类型错了，运行到那一行才崩溃。
本脚本补上这道检查，覆盖：

- `getString` / `stringResource` / `UiText.Res` / `R.string.x.asUiText(...)`
- `getQuantityString` / `pluralStringResource` / `UiText.Plural`（数量之后的才是格式化参数；
  `UiText.Plural` 不传参数时以数量为唯一参数）
- `ImportNoticeEntry(ImportNotice.X, listOf(...))`，按 `ImportIssueText.kt` 的映射找词条
- `JwErrorCode.X` 的构造点，按 `JwErrorText.kt` 的映射找词条

参数里有展开（`*args`）或词条 id 不是字面量（`error.messageRes`）的调用无法静态判断，跳过。

用法：python tools/check_string_args.py      退出码 1 表示有不一致。
"""
from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

BS = chr(92)
Q = chr(34)
SQ = chr(39)
WS = "[ " + chr(9) + chr(10) + chr(13) + "]*"
ID = "[A-Za-z0-9_]+"

# 有意只取模板、不在取词处代入参数的文件，以及原因。
TEMPLATE_FILES: dict[str, str] = {
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/transfer/IcsText.kt":
        "只取模板交给纯 Kotlin 的 IcsCalendar 代入（它要在无 Android 的单测里跑）",
}

MODULES = [
    "app", "core/model", "core/data", "core/ui",
    "feature/schedule", "feature/exam", "feature/edit", "feature/settings",
    "widget", "importer", "sync", "ocr",
]

PLACEHOLDER = re.compile("%(?:([0-9]+)[$])?[-#+ 0,(]*[0-9]*(?:[.][0-9]+)?([a-zA-Z])")


def placeholders(text: str) -> dict[int, str]:
    """位置 → 类型字母。非位置占位符按出现顺序编号。"""
    text = text.replace("%%", "")
    found: dict[int, str] = {}
    order = 0
    for match in PLACEHOLDER.finditer(text):
        order += 1
        index = int(match.group(1)) if match.group(1) else order
        found[index] = match.group(2)
    return found


def load_resources() -> dict[tuple[str, str], dict[int, str]]:
    """(string|plurals, 名称) → 占位符。plurals 取各档的并集。"""
    table: dict[tuple[str, str], dict[int, str]] = {}
    for module in MODULES:
        path = os.path.join(module, "src", "main", "res", "values", "strings.xml")
        if not os.path.isfile(path):
            continue
        for element in ET.parse(path).getroot():
            name = element.get("name")
            if element.tag == "string":
                table[("string", name)] = placeholders("".join(element.itertext()))
            elif element.tag == "plurals":
                merged: dict[int, str] = {}
                for item in element:
                    merged.update(placeholders("".join(item.itertext())))
                table[("plurals", name)] = merged
    return table


def split_args(text: str, i: int) -> tuple[list[str], int]:
    """text[i] 是左括号：返回括号内按顶层逗号切开的参数，以及右括号之后的位置。"""
    assert text[i] == "("
    args: list[str] = []
    depth = 0
    current = []
    j = i + 1
    n = len(text)
    while j < n:
        ch = text[j]
        if ch == Q:
            end = skip_string(text, j)
            current.append(text[j:end])
            j = end
            continue
        if ch == SQ:
            end = text.find(SQ, j + 2) + 1 if text[j + 1] == BS else j + 3
            current.append(text[j:end])
            j = end
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                arg = "".join(current).strip()
                if arg:
                    args.append(arg)
                return args, j + 1
            depth -= 1
        elif ch == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
            j += 1
            continue
        current.append(ch)
        j += 1
    return args, n


def skip_string(text: str, i: int) -> int:
    """text[i] 是双引号：返回字符串结束之后的位置（支持三引号与 ${} 里的嵌套字符串）。"""
    if text.startswith(Q * 3, i):
        end = text.find(Q * 3, i + 3)
        return len(text) if end < 0 else end + 3
    j = i + 1
    n = len(text)
    while j < n:
        ch = text[j]
        if ch == BS:
            j += 2
            continue
        if text.startswith("${", j):
            depth = 0
            j += 2
            while j < n:
                if text[j] == Q:
                    j = skip_string(text, j)
                    continue
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    if depth == 0:
                        break
                    depth -= 1
                j += 1
            j += 1
            continue
        if ch == Q:
            return j + 1
        j += 1
    return n


def line_of(text: str, i: int) -> int:
    return text.count(chr(10), 0, i) + 1


STRING_CALL = re.compile(
    "(getString|stringResource|UiText[.]Res|[^A-Za-z0-9_.]Res)" + "[(]" + WS + "(?:" + ID + "[.])?R[.]string[.](" + ID + ")"
)
AS_UI_TEXT = re.compile("(?:" + ID + "[.])?R[.]string[.](" + ID + ")[.]asUiText[(]")
PLURAL_CALL = re.compile(
    "(getQuantityString|pluralStringResource|UiText[.]Plural)" + "[(]" + WS + "(?:" + ID + "[.])?R[.]plurals[.](" + ID + ")"
)


def check_count(problems, path, text, pos, kind, name, args, resources):
    spec = resources.get((kind, name))
    if spec is None:
        return
    if any(a.startswith("*") for a in args):
        return
    expected = max(spec) if spec else 0
    where = f"{path}:{line_of(text, pos)}"
    if len(args) != expected:
        problems.append(f"{where}: {kind}/{name} 需要 {expected} 个参数，传了 {len(args)} 个")
        return
    for index, arg in enumerate(args, 1):
        if spec.get(index) == "d" and (arg.startswith(Q) or arg.startswith("UiText.")):
            problems.append(f"{where}: {kind}/{name} 第 {index} 个占位符是 %d，传的却是 {arg[:40]}")


def kotlin_sources():
    for module in MODULES:
        for path in sorted(glob.glob(os.path.join(module, "src", "main", "**", "*.kt"), recursive=True)):
            yield path.replace(BS, "/")


def check_calls(resources, problems):
    for path in kotlin_sources():
        if path in TEMPLATE_FILES:
            continue
        text = open(path, encoding="utf-8").read()
        for match in STRING_CALL.finditer(text):
            after = match.end()
            rest = re.match(WS, text[after:])
            k = after + rest.end()
            if text[k] == ",":
                call_open = text.rfind("(", match.start(), match.start(2))
                args, _ = split_args(text, call_open)
                check_count(problems, path, text, match.start(), "string", match.group(2), args[1:], resources)
            elif text[k] == ")":
                check_count(problems, path, text, match.start(), "string", match.group(2), [], resources)
        for match in AS_UI_TEXT.finditer(text):
            args, _ = split_args(text, match.end() - 1)
            check_count(problems, path, text, match.start(), "string", match.group(1), args, resources)
        for match in PLURAL_CALL.finditer(text):
            call_open = text.rfind("(", match.start(), match.start(2))
            args, _ = split_args(text, call_open)
            fmt = args[2:]
            if match.group(1) == "UiText.Plural" and not fmt:
                fmt = args[1:2]
            check_count(problems, path, text, match.start(), "plurals", match.group(2), fmt, resources)


def mapping(path: str, pattern: str) -> dict[str, tuple[str, str]]:
    if not os.path.isfile(path):
        return {}
    text = open(path, encoding="utf-8").read()
    result = {}
    for match in re.finditer(pattern, text):
        key = match.group(1)
        if match.group(2):
            result[key] = ("string", match.group(2))
        else:
            result[key] = ("plurals", match.group(3))
    return result


def list_args(text: str, k: int) -> list[str] | None:
    """text[k:] 以「, listOf(...)」或「, codeArgs = listOf(...)」开头时返回列表元素；否则返回 None。"""
    m = re.match(WS + "," + WS + "(?:codeArgs" + WS + "=" + WS + ")?" + "listOf[(]", text[k:])
    if not m:
        return None
    args, _ = split_args(text, k + m.end() - 1)
    return args


def check_ids(resources, problems):
    notice_map = mapping(
        "feature/settings/src/main/kotlin/com/nullclass/feature/settings/ImportIssueText.kt",
        "ImportNotice[.](" + ID + ") ->" + WS + "(?:Str[(]R[.]string[.](" + ID + ")|Quantity[(]R[.]plurals[.](" + ID + "))",
    )
    code_map = mapping(
        "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwErrorText.kt",
        "JwErrorCode[.](" + ID + ") ->" + WS + "(?:R[.]string[.](" + ID + ")|R[.]plurals[.](" + ID + "))",
    )
    for path in kotlin_sources():
        if path.endswith(("ImportIssueText.kt", "JwErrorText.kt", "ImportNotice.kt", "JwErrorCode.kt")):
            continue
        text = open(path, encoding="utf-8").read()
        for match in re.finditer("ImportNoticeEntry[(]" + WS + "ImportNotice[.](" + ID + ")", text):
            target = notice_map.get(match.group(1))
            if not target:
                continue
            args = list_args(text, match.end())
            if args is None:
                rest = re.match(WS + "[)]", text[match.end():])
                if not rest:
                    continue  # 参数不是 listOf 字面量，判断不了
                args = []
            check_count(problems, path, text, match.start(), target[0], target[1], args, resources)
        for match in re.finditer("JwErrorCode[.](" + ID + ")", text):
            target = code_map.get(match.group(1))
            if not target:
                continue
            args = list_args(text, match.end())
            check_count(problems, path, text, match.start(), target[0], target[1], args or [], resources)


def main() -> int:
    resources = load_resources()
    problems: list[str] = []
    check_calls(resources, problems)
    check_ids(resources, problems)
    for problem in problems:
        print(problem)
    if problems:
        print(chr(10) + f"发现 {len(problems)} 处参数与占位符不一致。")
        return 1
    print(f"通过：取词调用的参数与占位符一致（共 {len(resources)} 条词条）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
