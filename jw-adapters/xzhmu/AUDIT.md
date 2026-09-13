# 安全审计 —— 徐州医科大学研究生教务适配器（`xzhmu`）

审计对象：`jw-adapters/xzhmu/extract.js` + `parse.js` + `manifest.json`

上游出处：拾光课程表社区 `shiguang_warehouse` 的 `resources/XZHMU/xzhmu_01.js`
（<https://github.com/XingHeYuZhuan/shiguang_warehouse>，MIT，上游作者 **星河欲转**）
快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12 12:50:29 +0800），
同目录 `adapters.yaml` 里 `adapter_id: XZHMU_01`、`adapter_name: 徐州医科大学研究生教务`、
`category: POSTGRADUATE`、`maintainer: 星河欲转`。

移植者：**0x7E-2023**　日期：**2026-09-13**

## 结论

**通过。** 两段脚本都不发起任何网络请求，只读用户当前已登录那一页的课表表格；
不碰凭据、不读写存储、不写页面、没有第三方域、没有埋点。
唯一的交互是**问用户一句「现在是第几周」**（页面上没有周次线索时），
这个问题不含任何账号信息，桥不可用时自动跳过。

## 请求了哪些域与路径

**一个都没有。** 上游脚本与移植后的两段脚本全文没有
`fetch` / `XMLHttpRequest` / `sendBeacon` / `WebSocket` / `new Image().src`：

```bash
grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=" jw-adapters/xzhmu/*.js
```

上面这条**只命中两处注释**（两个文件头里说明「上游不请求任何接口」的那两句），没有一处是调用。
绝对 URL 也只出现在文件头的上游仓库链接里：

```bash
grep -noE "https?://[A-Za-z0-9._-]+" jw-adapters/xzhmu/*.js
# extract.js:5:https://github.com   parse.js:5:https://github.com   （都是注释里的出处链接）
```

`fixtures/*.extracted.json` 里的 `url` 字段是回归用例的**输入数据**（课表页地址），不是脚本发出的请求。

### 那 `allowHosts` 里的 `ehall.xzhmu.edu.cn` 是干什么的

不是给脚本用的，是给**页面自己**用的。上游 yaml 的登录入口是学校统一身份认证
（`authserver.xzhmu.edu.cn`），登录后落到办事大厅 `ehall.xzhmu.edu.cn`，课表页在那边 ——
`loginUrl` 与课表页**不同源**。而提取期间：

- 网络闸门 `JwNetworkGate` 只放行 `loginUrl` 的主机 + `allowHosts`（`JwAdapter.allowedHosts`），
  其余子资源一律拦掉；
- JS 沙箱（`JwScriptContract` 的 preamble）把 `fetch`/`XHR`/`WebSocket` 按同一份主机白名单包装，
  而且**提取结束后仍然生效**。

也就是说，不声明 `ehall.xzhmu.edu.cn` 的话，提取那一刻会把课表页自己的请求掐掉，
失败之后那页还会继续被 JS 层白名单拦着。声明的是一个**精确主机名**（没有通配），
它同时让 `JwOriginRules` 把提问桥注入到 ehall（通配项会被 `JwOriginRules` 跳过、拿不到桥，
所以这里不能用 `*.xzhmu.edu.cn`）。

## 读到了什么

只有用户当前打开那一页上的东西：

1. 课表表格 `table#kb.curriculum`（穿透最多两层同源 iframe 找）每一格的：
   整格文字、格子自带的星期属性 `w`、`rowSpan` / `colSpan`、`style` 里的 `display`、
   `hidden`，以及它在表格里的**网格列号**；
2. 课程格子里 `.C_kc_subject` 的段落文字：把 `<p>` 的 HTML 按 `<br>` 与空行切开后，
   每段里的 `<span>` 文字（课名 / 周次 / 地点）与夹在周次与地点之间的教师文本；
3. 页面上 `<select>` 的**当前选中项**文字：学期名候选（给学期起名）与「第N周」选择器（反推开学日）；
4. `document.title`、`location.href`、取数当天的本地日期；
5. 页面上没有周次线索时，问用户一句「现在是第几周」（1-30，可取消）。

**不含**学号 / 姓名 / 身份证 / 成绩 / 学籍 / 缴费 / `localStorage` / `cookie`。
`fixtures/` 里合成数据里的姓名（张三、李娜……）都是编的。

## 逐条对照移植手册 §5

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 全文没有 `password` / `pwd` / 登录表单 / `localStorage` / `cookie` 的读取（`parse.js` 里的 `tokens` 是**节次列表**，与令牌无关）；不注入脚本、不监听输入。提问桥只问「第几周」，不索要任何账号信息。 |
| 2 | 不外发 | ✅ 没有任何请求目标（见上）。没有 `fetch` / `XHR` / `sendBeacon` / `WebSocket` / `new Image().src`。 |
| 3 | 请求域可控 | ✅ 脚本不发起请求；`allowHosts` 只有一个精确主机 `ehall.xzhmu.edu.cn`，理由见上，没有通配。 |
| 4 | 只读课表 | ✅ 只读课表表格、页面 `<select>` 的选中项、`document.title`、`location.href`。不碰成绩、学籍、缴费、个人信息页。 |
| 5 | 不埋点 | ✅ 没有统计 / 上报 / 遥测；两段脚本连 `console.log` 都没有。 |
| 6 | 不 eval 远程代码 | ✅ 没有 `eval` / `new Function` / 字符串版 `setTimeout`；`setTimeout` 只接收函数（等表格出现用的轮询）。 |
| 7 | 不写页面 | ✅ 只读 DOM：`querySelector(All)` / `getAttribute` / `textContent` / `table.rows` / `options` / `innerHTML`（读）。**不往页面写任何东西**、不改表单、不触发提交、不点击、不插入节点。唯一的写操作是 `document.createElement('div')` 建一个**游离节点**（不插入文档），用来把格子里的 HTML 片段切成字段 —— 上游也是这么做的。 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 没有硬编码密钥、令牌、他人学号；脚本里没有任何真实账号数据，没有写死的主机（取数走当前页面）。 |

## 逐条对照批次三专项检查表

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 周次编码四写法 | ✅ `1-16周(单)` / `(单)1-16周` / `1-16(单周)` / `1-3,5-9周` 四种都在 `fixtures/weeks-and-columns` 里，逐条独立推出期望值。**上游这一处是坏的**：它的正则 `(\d+)(?:-(\d+))?\s*(?:\(?\s*([单双])\s*\)?)?` 在 `(单)1-16周` 上匹配不到前面的标记（标记落在 `(\d+)` 之前），整段塌成「每周都上」且不报警 —— 与测试方案 §3.1 里湖北医药学院那处同型。移植版把标记当成「段内属性 + 整串开头属性」两种位置都收。 |
| 2 | 括号内纯数字不是周次 | ✅ **原本是漏的，已修**。第一版这里写的「括号内容到不了周次解析器」不成立 —— 证伪者用三格 spans 实跑出反例：`(1-2)第2-8周` 读成第 1-2 周（真周次 2-8 被吃掉）、`3-16周,(1)` 凭空多出第 1 周、`第9-10节 第5-8周` 把**节次** 9-10 读成周次，三例都**不报警**。现在 `parseWeeks` 在解析周次**之前**：① 把「括号里只有数字 / 区间」的整组（`(1)` / `(1-2)` / 全角`（1）`）整组删掉 —— 括号里带「单 / 双」的不动，那是周次标记（`(单)1-16周`）；② 把带「节」的分段（`第9-10节` / `1-2节`）删掉，它是节次来源、由 `sectionLabelOf` 那条路径处理。排完认不出周次的格子走 `noWeekBlocks` 进 warnings，不硬凑一个周次。专打这条的夹具：`fixtures/serial-and-section`（3 个错例 + 3 个对照 + 1 个「节次写在周次后面」的顺序对照 + 2 个「删完认不出周次」的降级例）；变异 M7 / M8 各让它变红。 |
| 3 | 无星期表头兜底 | ✅ `extract.js` 交出**网格列号**（`col` / `colSpan` / `rowSpan`，按占用情况算，合并单元格不会让整行错位）与格子自带的星期属性；`parse.js` 先认格子自带的属性，再退回「网格列号 → 星期」的星期表头映射。**全文没有 `array.length - 7` 这类按数组长度猜列**，也没有拿 td 下标当列号（`parse.js` 里唯一的 `length` 算术是节次标签 `rowSpan` 的 token 偏移，与列无关；`grep -nE "length\s*-\s*[0-9]" parse.js` 只命中那一行）。两条都拿不到 → 进 warnings（`fixtures/no-day-column` 就是这一条，期望载荷里 `courses: []`，只有提示）。 |
| 4 | 时间合法性 | ✅ `periodTimes` 全部来自上游内置的 13 节作息表，逐条核过 `HH:mm` 且开始早于结束（独立校验脚本跑过）；本件**不从页面数据推算任何时间**，所以不存在算出 `24:00` / `85:45` 的路径。课表里出现第 14 节及以后时，时间给不出来 → 进 warnings。 |
| 5 | `warnings` 上限 | ✅ `warn()` 单条超 200 字先截断（往提示里嵌页面原文时还会先把原文裁到 40 字，免得把「请核对课表」挤掉）、总数到 20 条就停。各条提示都是**汇总计数**（一条顶多格），实测最坏用例 5 条、最长 79 字。 |
| 6 | 分页 | 不适用：本件读的是页面 DOM，没有接口、没有记录总数、不存在翻页；因此也不会「取完第一页就停」。 |
| 7 | 学期名 | ✅ 优先用页面 `<select>` 里教务自己写的学期名（`2026-2027学年第一学期`）；拿不到才用「**徐州医科大学** + 学年学期」（按导入日期推），并在 warnings 里说明是推算的。**没有**用适配器名当学期名。 |
| 8 | `teacher` 拿不到就留空 | ✅ 留空字符串，不写「未知」「暂无」。上游的 `teacher` 其实**恒为空**（见下「上游缺陷」），移植版修好后能取到就取。 |
| 9 | `allowHosts` | ✅ 写的是**精确主机名** `ehall.xzhmu.edu.cn`（不是通配 —— 通配既过宽、又会让 `JwOriginRules` 跳过、提问桥注入不进来）。脚本自身不请求，说明见上一节。 |
| 10 | 变异测试 | ✅ 做了 6 处，见下。每处都让对应用例变红。 |

## 上游缺陷（移植时修掉的）

1. **教师名恒为空**：上游 `extractTeacherBetweenSpans()` 用
   `document.createTreeWalker(root, NodeFilter.SHOW_TEXT, ...)` 取节点，再拿节点去和
   `spans[1]`（一个 **元素**）比相等 —— 文本节点永远不等于元素，`collecting` 从来不会打开，
   函数恒返回 `''`。移植版按文档顺序走 `childNodes`，命中起点 span 时跳过它自己的子树、
   中途的元素（教师名常常包在 `<a>` 里）继续下钻；取不到才留空。
2. **周次标记丢失**（见检查表第 1 条）。
3. **节次按「格子文本末尾的数字」认**：`sectionTextToNumber()` 用 `/(\d+)\s*$/`，
   标签写成「第1节」读不出来（整行被跳过），写成「1 08:00-08:40」会读出 `40`（时间里的分钟）。
   移植版改成「第N节 / 第N-M节」→「行首数字」→「与内置作息表的开始时间对齐」三条依次认，
   认不出来进 warnings。
4. **格子没有 `<p>` 时整格丢课**：上游只遍历 `<p>`，内容直接写在 `.C_kc_subject` 里就什么都读不到，
   而且不报警。移植版没有 `<p>` 就把该容器自身当一个段落读。

## 验证记录

**① fixture 五对**（`fixtures/basic`、`weeks-and-columns`、`no-day-column`、`serial-and-section`、`weeks-space-segments`），
期望值全部按规范**独立推出**（先写期望、再跑 `parse.js`，不是把输出贴回去），
用 Node 以 `__ncInput` 注入跑 `parse.js` 比对，四对全部 `MATCH`。
另外用一个**独立于 `parse.js` 的校验脚本**按规范逐条核过四份载荷（kind / totalWeeks 1..30 /
dayOfWeek 1..7 / startPeriod ≤ endPeriod / weekType 枚举 / endWeek ≤ totalWeeks /
firstDay 是 ISO / periodTimes 是 HH:mm 且开始早于结束 / warnings ≤20 条且每条 ≤200 字 /
课程三元组不重复 / periodIndex 不重复）—— 全过。

`serial-and-section` 是本轮补的（批次三检查表第 2 条的零覆盖）：一个格子一行、行号即节次，
九门课分别是 `(1-2)第2-8周`（错例，期望 2-8）、`3-16周,(1)`（错例，期望 3-16）、
`第9-10节 第5-8周`（错例，期望 5-8）、`第2-8周` / `(1)第2-8周` / `（1-2）第10-12周`（对照）、
`第14-15周 第9-10节`（节次写在周次后面的顺序对照）、`第9-10节` 与 `(1-2)`（纯节次 / 纯序号，
删完认不出周次 → 进 `warnings`、这两门课不进 `courses`）。

**② 变异测试**（把 `parse.js` 里对应逻辑故意改坏 → 哪条用例变红）：

| 变异 | 改坏的地方 | 变红的用例 |
|---|---|---|
| M1 | 单双周标记只取整串开头的（段内标记丢掉） | `weeks-and-columns` |
| M2 | 用行内 td 下标当列号（`byCol[j + 1]`）而不是网格列号 | `weeks-and-columns` |
| M3 | 节次标签格 rowSpan 盖住多行时，节次不再往后发（恒取 `tokens[0]`） | `weeks-and-columns` |
| M4 | 不跳 `display:none` 的隐藏占位格 | `basic` |
| M5 | 没星期信息时按行宽猜列（`cells.length - 1`，即 `row.length - 7` 那类） | `no-day-column` |
| M6 | 周次不做极大段合并（每周单独一段 `ALL`） | `basic`、`weeks-and-columns` |
| M7 | 括号里纯数字 / 区间的整组不删（规则① 整行失效，即「不删括号、直接解析」） | `serial-and-section` |
| M8 | 带「节」的分段不删（规则② 整行失效） | `serial-and-section` |

其中 M1、M7、M8 是**在原地改**的：改完跑用例（对应的那条 = DIFF，其余几条仍 MATCH），
再从备份还原，`diff` 无差异、`sha256` 与改前一致。
M7 / M8 各自让 `serial-and-section` 变红的**具体表现**（改坏后跑出来的，与期望对照）：
不删括号 → `课程甲` 变成 `w1-2`、`课程乙` 变成 `w1-3 ODD` + `w4-16`、`课程辛` 变成 `w1-2`，
另外 `课程庚` 这个本该因认不出周次被跳过的格子凭空出现在课表里（`w1-2`）；
不删节次分段 → `课程丙` 变成 `w9-10`、`课程壬` 变成 `w14-30`（节次数字 `9-10` 粘到周次后面，
`14-15` 被读成 `14-159` 再截到 30），`课程己` 凭空出现（`w9-10`）。
两条另有三条老用例仍 MATCH —— 说明这个缺陷**只有新用例盖得住**。
M1 那轮（本轮 B5 修复之前）的 `sha256` 是
`40e9618f424ead5e62323208b06f671dfa062ad6d31aa3739f559ca92820c026`；
B5 修复 + 头部 ⑨ 注释之后的全量校验值是
`ab9729bcae0f65a9c74be09822228ce91a8a52e1b8bd63acb4300f2341847176`
（M7 / M8 各改坏一次后都还原回这个值）。其余五处（M2–M6）改的是内存里的副本，工作区文件从未被写过。

**③ `extract.js` 冒烟测试**（不在仓库里，临时用手搓的迷你 DOM 跑真页面结构）：
`table#kb.curriculum` + `td[w]` + `.C_kc_subject > p > span` 的合成页面喂给 `extract.js`，
输出再喂给 `parse.js`，核对到：连堂 `rowSpan=2` → 第 1-2 节、`display:none` 占位格被跳过、
一格两门课（`<br><br>` 分隔）切成两门、没有 `<p>` 的格子照样读出来、
教师名取到（张三 / 李娜 / 赵敏 / 孙磊 / 陈晨 / 王芳）、
没有 `w` 属性的格子退回星期表头对到星期一、带 `w` 的格子优先按属性对到星期三/星期五、
学期名取到页面 `<select>` 的选中项、周次取自页面的「第N周」选择器（`currentWeek=3`）。
另外单独验了两条降级路径：页面上没有周次选择器时会问用户（`currentWeekSource: "ask"`）；
`__ncCapabilities.ask` 为 false 时**不问**，直接退回「最近的周一」并在 warnings 里说明。

## 已知边界（不是安全问题的说明）

- **平台没能按接口路径证实**：上游脚本不请求任何接口（见上），所以「强智 eams」这个说法
  在脚本里**没有证据**，只有上游注释、`adapters.yaml` 与页面结构（真 `<table>`、`#kb.curriculum`、
  `td[w]`、`.C_kc_subject`）作旁证。本适配器不依赖这个判断：它只读 DOM，结构不对时进 warnings。
- **`fixtures/` 是合成的**（维护者手上没有该校账号）。它们只保证「同样的输入永远给同样的输出」，
  不保证解析在真实页面上一字不差；拿到真实 dump 请替换并重跑门。
- **研究生端**：上游脚本只有一条 DOM 读取路径，没有本科生 / 研究生双分支，
  因此不存在「选错分支」的问题；研究生身份只体现在上游 yaml 的 `category` 上。
- **真机未验**：没有账号，第 ③ 层（真机抽验）没做。若课表页不在 `ehall.xzhmu.edu.cn`
  而是别的子域，提问桥不会注入到那一页（提问自动降级为「最近的周一」+ 提示），
  页面自身的请求也不在白名单里 —— 这是 `allowHosts` 只写一个精确主机的代价，
  发现是别的域时改一行 `allowHosts` 即可。
