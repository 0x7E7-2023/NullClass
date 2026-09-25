#!/usr/bin/env python3
"""扫描 Kotlin 源码中残留的中文字符串字面量（含只有全角标点的）。

界面文案必须放在 strings.xml 里（见 docs/i18n.md），源码中不应再出现面向用户的中文。
本脚本按 Kotlin 词法逐字符扫描（含字符串模板 ${...} 里嵌套的字符串与字符字面量），跳过注释，
并跳过 ALLOWLIST 中登记的例外文件，以及行尾注释里标了 `i18n-exempt:` 的那几行
（解析用的中文关键词、写进数据库的数据、给开发者看的日志等）。

另外扫清单文件与 res/layout、res/menu、res/xml 里写死的 android:label / text / title 等属性。

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
        "缺省学期名「拾光课表」：写进数据库的数据，会同步到其他设备，不随界面语言变化（提示一律走 ImportNotice）",
    "importer/src/main/kotlin/com/nullclass/importer/wakeup/WakeUpParser.kt":
        "缺省学期名与补出的课名：写进数据库的数据，会同步到其他设备，不随界面语言变化",
    "core/model/src/main/kotlin/com/nullclass/core/model/Exam.kt":
        "考试标题的默认值：存进数据库的数据内容，新建时由界面层按当前语言填入",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwManifest.kt":
        "适配器 manifest 的规范校验；「请先升级」两条带 JwErrorCode，界面按码取词条",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwSchedulePayload.kt":
        "适配器返回数据的规范校验：枚举 JSON 字段名与取值，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwPackageReader.kt":
        "适配器包（zip / 目录）的结构校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwLibraryIndex.kt":
        "适配器库索引的规范校验；用户填错库地址的几条带 JwErrorCode，界面按码取词条",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwBuiltinLibrary.kt":
        "内置适配器库的装配断言：构建接线有问题时给开发者看，不发版",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwOfficialLibrary.kt":
        "官方适配器库的验签与解包校验：攻防边界上的原始诊断，不翻；「没有更新的版本」带 JwErrorCode",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwRemoteFetcher.kt":
        "适配器库网络拉取的中文原文只进日志；读不到索引/文件两条带 JwErrorCode",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwUserAdapterStore.kt":
        "用户适配器落盘的校验原文（搜索、日志用）；界面按 JwBrokenAdapter.error 取词条",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwHostAllowlist.kt":
        "allowHosts 通配域名的校验：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwAdapterRepository.kt":
        "适配器 key 与内置项冲突的提示：规范诊断，写给适配器作者看",
    "importer/src/main/kotlin/com/nullclass/importer/jw/JwAsk.kt":
        "适配器 ask 接口的参数校验：规范诊断，写给适配器作者看",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwScriptBridge.kt":
        "桥调用的回填文本：走适配器脚本这条协议通道，是给适配器作者的诊断，不是界面文案",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwOcrBridge.kt":
        "OCR 桥回填给适配器脚本的协议诊断（次数上限、超时）；给用户的换图建议走词条",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/JwScriptRunner.kt":
        "脚本执行失败的原文（日志、回填脚本）；宿主限额的用户提示由 JwScriptLimitException 取词条",
    "feature/settings/src/main/kotlin/com/nullclass/feature/settings/jw/OkHttpJwRemoteFetcher.kt":
        "适配器库下载失败的中文原文只进日志；用户看到的由 JwErrorCode 取词条（见 JwErrorText.kt）",
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

BS = chr(92)   # 反斜杠
NL = chr(10)   # 换行
Q = chr(34)    # 双引号
SQ = chr(39)   # 单引号
TQ = Q * 3


class KotlinLexer:
    """
    最小的 Kotlin 词法扫描：找出所有字符串 / 字符字面量，并记下每行的行尾注释。

    字符串模板 `${...}` 里可以再写完整的字符串（`"${if (x) "甲" else "乙"}"`），
    所以遇到 `${` 时递归扫描表达式，内层字面量单独产出；外层字面量的文本里去掉表达式部分。
    """

    def __init__(self, text: str):
        self.text = text
        self.i = 0
        self.line = 1
        self.comments: dict[int, str] = {}

    def literals(self):
        yield from self._code(until_brace=False)

    def _peek(self, s: str) -> bool:
        return self.text.startswith(s, self.i)

    def _advance(self, n: int = 1):
        for _ in range(n):
            if self.i < len(self.text) and self.text[self.i] == NL:
                self.line += 1
            self.i += 1

    def _code(self, until_brace: bool):
        depth = 0
        n = len(self.text)
        while self.i < n:
            if self._peek("/*"):
                self._block_comment()
            elif self._peek("//"):
                start_line = self.line
                j = self.text.find(NL, self.i)
                j = n if j < 0 else j
                self.comments[start_line] = self.comments.get(start_line, "") + self.text[self.i:j]
                self.i = j
            elif self._peek(TQ):
                yield from self._string(raw=True)
            elif self._peek(Q):
                yield from self._string(raw=False)
            elif self._peek(SQ):
                yield from self._char()
            elif until_brace and self._peek("{"):
                depth += 1
                self._advance()
            elif until_brace and self._peek("}"):
                if depth == 0:
                    self._advance()
                    return
                depth -= 1
                self._advance()
            else:
                self._advance()

    def _block_comment(self):
        depth = 0
        n = len(self.text)
        while self.i < n:
            if self._peek("/*"):
                depth += 1
                self._advance(2)
            elif self._peek("*/"):
                depth -= 1
                self._advance(2)
                if depth == 0:
                    return
            else:
                self._advance()

    def _string(self, raw: bool):
        start_line = self.line
        quote = TQ if raw else Q
        self._advance(len(quote))
        parts = []
        inner = []
        n = len(self.text)
        while self.i < n:
            if raw and self._peek(TQ):
                # 结尾可以多出引号（"""a"""" 的最后一个引号属于内容）
                while self._peek(Q * 4):
                    parts.append(Q)
                    self._advance()
                self._advance(3)
                break
            if not raw and self._peek(Q):
                self._advance()
                break
            if not raw and self._peek(NL):
                break  # 未闭合，按行结束处理
            if not raw and self._peek(BS):
                parts.append(self.text[self.i:self.i + 2])
                self._advance(2)
                continue
            if self._peek("${"):
                self._advance(2)
                inner.extend(self._code(until_brace=True))
                parts.append("${}")
                continue
            parts.append(self.text[self.i])
            self._advance()
        yield start_line, quote + "".join(parts) + quote
        yield from inner

    def _char(self):
        start_line = self.line
        j = self.i + 1
        if self.text.startswith(BS, j):
            j += 2
        else:
            j += 1
        if j < len(self.text) and self.text[j] == SQ:
            literal = self.text[self.i:j + 1]
            self._advance(j + 1 - self.i)
            yield start_line, literal
        else:
            self._advance()


def string_literals(text: str):
    """产出 (起始行号, 字面量文本)。注释不计入；字符字面量与模板里嵌套的字符串计入。"""
    yield from KotlinLexer(text).literals()


# 行级例外：在那一行的行尾注释里写上 `i18n-exempt: <原因>`，该行的中文字面量就会被跳过。
# 比 ALLOWLIST 细一档，用于「同一文件里既有界面文案、又有必须保留的中文」的场合
# （写进数据库的数据、解析关键词、给开发者看的日志）。只认注释 —— 写在字符串里不算。
EXEMPT_MARKER = "i18n-exempt:"


def scan(path: str) -> list[tuple[int, str]]:
    text = open(path, encoding="utf-8").read()
    lexer = KotlinLexer(text)
    # 先扫完整个文件：行尾注释在字面量之后才扫到，边扫边判豁免会漏看
    found = list(lexer.literals())
    hits = []
    for line, literal in found:
        if not CJK.search(literal):
            continue
        if EXEMPT_MARKER in lexer.comments.get(line, ""):
            continue
        hits.append((line, literal.replace(NL, BS + "n")[:120]))
    return hits


def kotlin_files(root: str):
    if os.path.isfile(root):
        if root.endswith(".kt"):
            yield root.replace(BS, "/")
        return
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "build"]
        for name in sorted(filenames):
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name).replace(BS, "/")


# 会显示给用户的 XML 属性：值必须引用资源（@string/...），不能写死文字
XML_TEXT_ATTR = re.compile(
    r'android:(label|text|title|contentDescription|hint|summary|description)=' + Q + r'([^' + Q + r']*)' + Q
)
XML_DIRS = ("layout", "menu", "xml")


def xml_files(module: str):
    manifest = os.path.join(module, "src", "main", "AndroidManifest.xml")
    if os.path.isfile(manifest):
        yield manifest.replace(BS, "/")
    res = os.path.join(module, "src", "main", "res")
    if not os.path.isdir(res):
        return
    for entry in sorted(os.listdir(res)):
        if entry.split("-")[0] in XML_DIRS:
            folder = os.path.join(res, entry)
            for name in sorted(os.listdir(folder)):
                if name.endswith(".xml"):
                    yield os.path.join(folder, name).replace(BS, "/")


def scan_xml(path: str) -> list[tuple[int, str]]:
    hits = []
    for number, line in enumerate(open(path, encoding="utf-8"), 1):
        for match in XML_TEXT_ATTR.finditer(line):
            value = match.group(2)
            if value and not value.startswith(("@", "?")) and (CJK.search(value) or re.search("[A-Za-z]{2,}", value)):
                hits.append((number, match.group(0)))
    return hits


def main(argv: list[str]) -> int:
    explicit = argv[1:]
    roots = explicit or [os.path.join(r, "src", "main") for r in DEFAULT_ROOTS]
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
    if not explicit:
        for module in DEFAULT_ROOTS:
            for path in xml_files(module):
                for line, attr in scan_xml(path):
                    print(f"{path}:{line}: {attr}")
                    failures += 1
    if failures:
        print(NL + f"残留 {failures} 处写死的文字。界面文案请移入 strings.xml（见 docs/i18n.md）；")
        print("确属数据或开发者信息的，登记到本脚本的 ALLOWLIST 并写明原因。")
        return 1
    print(f"通过：未发现残留的中文字面量（跳过 {skipped} 个已登记的例外文件）。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
