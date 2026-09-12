# 安全审计：`hynu`（衡阳师范学院）

- 上游：`shiguang_warehouse` 的 `HYNU/hynu_01.js`（MIT，作者 **星河欲转**），快照 2026-09-12 的副本。
- 移植者：NullClass（0x7E7-2023）　日期：**2026-09-12**
- 平台：**强智科技「高校综合管理教务系统」（湖南强智科技）学生端 `/jsxsd/`**，不是正方。
  依据：上游 `adapters.yaml` 的 `import_url` 是 `https://hysfjw.hynu.edu.cn/jsxsd/`；学校官网的注册通知
  也写明教务系统入口是 `https://hysfjw.hynu.edu.cn/jsxsd/`；`/jsxsd/` + `xskb/xskb_list.do` 是强智学生端
  的路径（正方新版是 `/jwglxt/`，课表接口 `xskbcx_cxXsKb.html`）。上游自称「强智适配」是对的。

**结论：通过。** 手册 §5 的 8 条逐条过完，没有命中；适配器只做「取课表」一件事。

## 请求域（全部）

| 域 | 请求 | 说明 |
|---|---|---|
| `hysfjw.hynu.edu.cn` | `GET /jsxsd/xskb/xskb_list.do` | 课表页（当前学期）。只在当前页面没有课表时才发 |
| `hysfjw.hynu.edu.cn` | `POST /jsxsd/xskb/xskb_list.do` | 取指定学年学期的课表，body 与上游一字不差：`cj0701id=&zc=&demo=&xnxq01id=<学期>` |

`allowHosts` = `["hysfjw.hynu.edu.cn"]`（精确主机，不用通配）。没有第二个域。

```bash
$ grep -oE "https?://[A-Za-z0-9.-]+" hynu_01.js | sort -u
https://hysfjw.hynu.edu.cn
$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|eval\(|new Function|password|pwd" hynu_01.js
193:        const response = await fetch("https://hysfjw.hynu.edu.cn/jsxsd/xskb/xskb_list.do", {
```

即上游全脚本只有一个绝对 URL、一处 `fetch`，没有存储、没有 `eval`、没有凭据相关标识符。

## 逐条（移植手册 §5）

1. **不碰凭据** — 不读 `password`/`pwd`/登录表单/`localStorage`/令牌，提取全程在用户已登录的页面里发生。
   上游让用户手输的是**学年**（不是账号密码），移植后连这也不问了。
2. **不外发** — 两处请求都指向本校教务。没有 `sendBeacon`/`WebSocket`/`EventSource`/`new Image().src`，
   没有重定向后二次外发，`parse.js` 完全不联网（纯转换）。
3. **请求域可控** — 只有一个精确主机，见上。
4. **只读课表** — 只请求课表页。不碰成绩、学籍、考勤、缴费、个人信息。
   `extract.js` 读的页面内容只有课表表格与「与学期有关」的几处文字，逐条列在下面的
   **「读取面」**一节（这是本文件里**唯一**该被当作读取面依据的地方）。
   页面上的其它内容不读；读到的文字里即使夹着姓名/学号，也只有匹配到的那一小段会留下，
   进载荷的只有课表与学期名。
5. **不埋点** — 无统计、无上报、无遥测，也没有任何「回传失败原因」的请求。
6. **不 eval 远程代码** — 无 `eval` / `new Function`；取回的 HTML 只交给 `DOMParser`（惰性文档，不执行脚本）。
7. **不写页面** — 不写 DOM、不改表单、不触发提交、不点页面元素。上游往页面写过一个
   `window.validateYearInput`（给它的输入校验用的全局函数），移植后**连同弹窗一起删掉了**，
   `extract.js` 对页面只读。
8. **不依赖用户输入之外的秘密** — 没有硬编码密钥、令牌、他人学号；`xnxq01id` 来自页面下拉框。

## 读取面（`extract.js` 到底读了页面上的什么）

第 4 条说的是「读什么」，这里说「怎么读、读到哪一层」。本节 2026-09-12 与代码逐条对齐过
（给的是函数名，不给行号 —— 行号会漂）。

| # | 读什么 | 代码位置 | 取出的值 | 去处 |
|---|---|---|---|---|
| 1 | 课表表格：`#timetable` / `#kbtable`，都没有时含 `.kbcontent` 的那张表 | `findTable()` → `rowsOfTable()` | 每个单元格的文字 + `div.kbcontent` 的原始 HTML | 载荷 `rows[]`（由 `parse.js` 解释） |
| 2 | 「学年学期」下拉框：id 或 name 含 `xnxq` 的 `<select>` | `readTerm()` 主路径 | 选中项的 value（`2026-2027-1`）与选项文字（学期名） | 载荷 `term` |
| 3 | 兜底①：`document.title` | `termTextHints()` | 只做一次正则匹配 | 见下 |
| 4 | 兜底②：页面上**每个** `<select>` 的 option 文字 | `termTextHints()` | 同上 | 见下 |
| 5 | 兜底③：id 或 class 含 `xnxq` 的元素的**短**文本（≤120 字） | `termTextHints()` | 同上 | 见下 |

兜底（3-5）只在下拉框认不出学期时才走，实现在 `termTextHints()` 一个函数里。
它是**唯一**从「非课表结构」取文字的地方，行为如下：

- 只在这三处取，**不读 `document.body`**（第 5 项还带长度上限，避免命中一个包着整页的容器）；
- 取到的整串文字只在本页内存里做一次正则匹配
  `(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期`；
- 匹配到了，**只把匹配到的那一小段**（如 `2026-2027学年第一学期`）当学期名，
  `term.code` 由正则的三个捕获组拼出来；没匹配到的文字不留引用、不记日志；
- 无论匹配与否都**不外发、不进载荷**、不写页面。`term.name` / `term.code` 是这条路径上
  唯一可能进载荷的东西，而且它们是学期名，不是页面上的其它内容。

**这条路径 2026-09-12 被收窄过**：原实现是 `clean(doc.body ? doc.body.textContent : '')`，
把**整页可见文字**（课表页顶部通常带学生姓名/学号）读进内存再做匹配。值没有外发、也不进载荷，
但当时本节之外的第 4 条把它写成了「只读课表表格与下拉框选项」——**审计文本比代码窄**。
现在代码收窄到上面三处、本节如实列出这三处，两边一致。代价是覆盖少了一点：
「学期名只出现在正文其它位置、既不在标题也不在任何下拉框里」的页面形状兜底会落空，
那时会走明确报错而不会猜一个学期名（见文末「已知不确定」）。

## 移植时删掉的上游行为

- 弹窗与 toast（`showAlert` 确认已登录、`showPrompt` 输学年、`showSingleSelection` 选学期、
  一串 `showToast`）：空课的导入流程自己会确认；学期改成从课表页下拉框里取**选中的那一项**
  ——用户在页面上切学期，适配器就跟着他走，比弹窗更清楚。
- 上游硬编码的 `semesterTotalWeeks: 20` / `firstDayOfWeek: 1` / `savePresetTimeSlots`：
  20 周与 12 节作息表在 `parse.js` 里保留为**载荷字段**（`totalWeeks` / `periodTimes`），
  并在 `warnings` 里说明它们是适配器内置值而不是教务给的。
- 上游没有开学日期（`config.semesterStartDate` 也没给）：移植后按 §4.2 的「最近的周一」推算，
  并在 `warnings` 里如实说明。

## 已知不确定（交给真机抽验与用户反馈）

- 课表容器的 id（上游用 `#timetable`，别的强智学校见过 `#kbtable`）与「课程名是纯文本还是
  `font[title=课程]`」这类细节没有真实页面可验，做了兜底但没在真机上跑过。
- 学期下拉框的 id 里含 `xnxq` 是强智的通行写法（`xnxq01id`），同样没在真机验过；
  认不出来时先走「读取面」里那三处收窄过的兜底（页面标题 / 下拉框选项 / `xnxq` 元素的文字），
  再认不出才明确报错，不会静默导错学期。兜底收窄的代价：学期名若只出现在正文其它位置
  （既不在标题、也不在任何下拉框里），这条兜底会落空 —— 那时停在报错上等用户反馈，
  不猜学期名。
- fixture 是**合成的**（见 `fixtures/basic.extracted.json` 的 `_note`）：它只保证同一份输入
  永远得到同一份输出，**不保证**真实页面上的解析是对的。
