#!/usr/bin/env python3
"""扫描 Kotlin 源码中残留的中文字符串字面量（含只有全角标点的）。

界面文案必须放在 strings.xml 里（见 docs/i18n.md），源码中不应再出现面向用户的中文。
本脚本按 Kotlin 词法逐字符扫描，跳过注释与字符字面量，只报字符串字面量里的中文，
并跳过 ALLOWLIST 中登记的例外文件，以及行内标了 `i18n-exempt:` 的那几行
（解析用的中文关键词、写进数据库的数据、给开发者看的日志等）。

用法：
    python tools/check_hardcoded_text.py              # 扫描全部模块的 main 源码
    python tools/check_hardcoded_text.py <路径>...     # 扫描指定的文件或目录
退出码 1 表示有残留。
"""
from __future__ import annotations

import os
import re
import sys

# 汉字，加上中文专用的全角标点（、；：「」（）等，U+3000–303F 与 U+FF00–FFEF）——
# 只含标点的字面量同样是中文排版：`joinToString("、")` 换了语言就是错的分隔符。
CJK = re.compile("[一-鿿" + chr(0x3000) + "-" + chr(0x303F) + chr(0xFF00) + "-" + chr(0xFFEF) + "]")

# 允许保留中文字面量的文件，以及原因。这些内容是数据或开发者信息，不是界面文案。
ALLOWLIST: dict[str, str] = {
    "core/model/src/main/kotlin/com/nullclass/core/model/CourseColorKeywords.kt":
        "课程名配色关键词表：匹配用的数据，不显示给用户",
    "importer/src/main/kotlin/com/nullclass/importer/jw/ocr/JwCourseTextParser.kt":
        "教务页面 OCR 文本的解析规则：匹配用的中文关键词",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwScheduleNormalizer.kt":
        "适配器返回的载荷类型不对：规范诊断，写给适配器作者看",
    "ocr/src/main/kotlin/com/nullclass/ocr/OcrImage.kt":
        "内部不变量断言：参数越界时给开发者看，不会展示给用户",
    "core/model/src/main/kotlin/com/nullclass/core/model/DefaultTimetable.kt":
        "迁移时写进数据库的默认课表名：数据内容，会同步到其他设备，不随界面语言变化",
    "importer/src/main/kotlin/com/nullclass/importer/shiguang/ShiguangParser.kt":
        "缺省学期名「拾光课表」：写进数据库的数据，会同步到其他设备，不随界面语言变化",
    "importer/src/main/kotlin/com/nullclass/importer/wakeup/WakeUpParser.kt":
        "缺省学期名与补出的课名：写进数据库的数据，会同步到其他设备，不随界面语言变化",
    "core/model/src/main/kotlin/com/nullclass/core/model/Exam.kt":
        "考试标题的默认值：存进数据库的数据内容，新建时由界面层按当前语言填入",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwManifest.kt":
        "适配器 manifest 的规范校验：枚举 JSON 字段名与取值，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwSchedulePayload.kt":
        "适配器返回数据的规范校验：枚举 JSON 字段名与取值，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwPackageReader.kt":
        "适配器包（zip / 目录）的结构校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwLibraryIndex.kt":
        "适配器库索引的规范校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwBuiltinLibrary.kt":
        "内置适配器库的装配断言：构建接线有问题时给开发者看，不发版",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwOfficialLibrary.kt":
        "官方适配器库的验签与解包校验：攻防边界上的原始诊断，不翻",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwRemoteFetcher.kt":
        "适配器库网络拉取的校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwUserAdapterStore.kt":
        "用户适配器落盘的校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwHostAllowlist.kt":
        "allowHosts 通配域名的校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwAdapterRepository.kt":
        "适配器 key 与内置项冲突的提示：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwAsk.kt":
        "适配器 ask 接口的参数校验：规范诊断，写给适配器作者看",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwScriptBridge.kt":
        "桥调用的回填文本：走适配器脚本这条协议通道，是给适配器作者的诊断，不是界面文案",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwOcrBridge.kt":
        "OCR 桥的失败原因：回填给适配器脚本，是给适配器作者的诊断，不是界面文案",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwScriptRunner.kt":
        "脚本执行失败的原文：既回填给适配器脚本，也写进失败日志，是给适配器作者的诊断",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/OkHttpJwRemoteFetcher.kt":
        "适配器库下载失败的原因：协议层诊断（含 HTTP 状态码），随异常一起给适配器作者看",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwExtractLog.kt":
        "adb / logcat 日志：给开发者看的原始信息",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwScriptContract.kt":
        "注入网页的 JS 模板源码：报错栈里回传给适配器作者，是脚本不是文案",
}

DEFAULT_ROOTS = [
    "app", "core/model", "core/data", "core/ui",
    "feature/schedule", "feature/exam", "feature/edit", "feature/settings",
    "widget", "importer", "sync", "ocr",
]


def string_literals(text: str):
    """产出 (起始行号, 字面量文本)。按 Kotlin 词法扫描，注释与字符字面量不计入。"""
    i, n = 0, len(text)
    line = 1
    while i < n:
        ch = text[i]

        if ch == "\n":
            line += 1
            i += 1
            continue

        # 块注释：Kotlin 的 /* */ 可嵌套
        if text.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    if text[i] == "\n":
                        line += 1
                    i += 1
            continue

        # 行注释
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue

        # 原始字符串
        if text.startswith('"""', i):
            start_line = line
            j = i + 3
            while j < n and not text.startswith('"""', j):
                if text[j] == "\n":
                    line += 1
                j += 1
            j = min(j + 3, n)
            yield start_line, text[i:j]
            i = j
            continue

        # 普通字符串
        if ch == '"':
            start_line = line
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n":  # 未闭合，按行结束处理
                    break
                j += 1
            j = min(j + 1, n)
            yield start_line, text[i:j]
            i = j
            continue

        # 字符字面量：'x' / '\n' / '\'' —— 里面的引号不能当成字符串开头
        if ch == "'":
            j = i + 1
            if j < n and text[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and text[j] == "'":
                j += 1
            i = j
            continue

        i += 1


# 行级例外：在那一行写上 `i18n-exempt: <原因>` 注释，该行的中文字面量就会被跳过。
# 比 ALLOWLIST 细一档，用于「同一文件里既有界面文案、又有必须保留的中文」的场合
# （写进数据库的数据、解析关键词、给开发者看的日志）。
EXEMPT_MARKER = "i18n-exempt:"


def scan(path: str) -> list[tuple[int, str]]:
    text = open(path, encoding="utf-8").read()
    lines = text.split("\n")
    hits = []
    for line, literal in string_literals(text):
        if not CJK.search(literal):
            continue
        if 0 < line <= len(lines) and EXEMPT_MARKER in lines[line - 1]:
            continue
        hits.append((line, literal.replace("\n", "\\n")[:120]))
    return hits


def kotlin_files(root: str):
    if os.path.isfile(root):
        if root.endswith(".kt"):
            yield root.replace("\\", "/")
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "build"]
        for name in sorted(filenames):
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name).replace("\\", "/")


def main(argv: list[str]) -> int:
    roots = argv[1:] or [os.path.join(r, "src", "main") for r in DEFAULT_ROOTS]
    failures = 0
    skipped = 0
    for root in roots:
        for path in kotlin_files(root):
            if any(path.endswith(k) for k in ALLOWLIST):
                skipped += 1
                continue
            for line, literal in scan(path):
                print(f"{path}:{line}: {literal}")
                failures += 1
    if failures:
        print(f"\n残留 {failures} 处中文字面量。界面文案请移入 strings.xml（见 docs/i18n.md）；")
        print("确属数据或开发者信息的，登记到本脚本的 ALLOWLIST 并写明原因。")
        return 1
    print(f"通过：未发现残留的中文字面量（跳过 {skipped} 个已登记的例外文件）。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
