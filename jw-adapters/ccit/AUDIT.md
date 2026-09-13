# 安全审计：`ccit`（长春工程学院）

- **平台**：强智科技「高校综合管理教务系统」学生端（接口路径 `/jsxsd/...`）。判据是**脚本实际请求的
  接口路径**，不是上游注释 —— 上游 `ccit.js` 只发一个请求，路径就是 `/jsxsd/xskb/xskb_list.do`。
- **入口**：WebVPN（`https://webvpn.ccit.edu.cn/auth/login`）。
- **上游出处**：`shiguang_warehouse` 的 `resources/CCIT/ccit.js`，
  commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12 12:50:29 +0800），MIT，作者 **星河欲转**。
  同目录 `resources/CCIT/adapters.yaml`：`adapter_name: 长春工程学院强智教务`、`maintainer: 星河欲转`、
  `import_url: https://webvpn.ccit.edu.cn/auth/login`。
- **移植者**：0x7E-2023　**日期**：2026-09-13
- **取数方式**：DOM 抓取（强智课表页返回服务端渲染的 HTML，不是 JSON）。两级：
  ① 当前页面就有课表表格（`#kbtable` 里有 `.kbcontent`）→ 直接用，**一个请求都不发**；
  ② 否则同源请求 `/jsxsd/xskb/xskb_list.do`（POST 带 `xnxq01id`，或 GET 让教务用自己的默认学期）。

---

## 1. 脚本实际请求了哪些域与路径（全部）

### 上游脚本

对上游 `ccit.js` 静态扫：

```
$ grep -oE "https?://[A-Za-z0-9._-]+" resources/CCIT/ccit.js | sort -u
https://http-10-198-47-148-8080.webvpn.ccit.edu.cn

$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|eval\(|new Function" resources/CCIT/ccit.js
235:        const response = await fetch("https://http-10-198-47-148-8080.webvpn.ccit.edu.cn/jsxsd/xskb/xskb_list.do", {
```

**上游只有一条绝对 URL、一次 `fetch`**，没有别的域、没有 XHR / WebSocket / sendBeacon /
`new Image().src` / `eval` / `new Function`。请求体是：

```
jx0404id=&cj0701id=&zc=&demo=&xnxq01id=<起始学年>-<起始学年+1>-<1|2>
```

（`xnxq01id` 是上游客厅弹窗问出来的：先 `showPrompt` 让用户手输四位起始学年，再
`showSingleSelection` 选「第一学期/第二学期」。）

### 这所学校的特殊之处：主机名是 WebVPN 现场编码出来的

`http-10-198-47-148-8080.webvpn.ccit.edu.cn` 不是一台固定的服务器名，它是网关把**内网地址**
`http://10.198.47.148:8080` 编码进主机名的结果。换一次会话、换一台内网服务器，这个名字就变了。
上游把它写死，等于把「那一次会话的映射结果」当成常量。

### 本适配器怎么请求

**不硬编码任何主机名**，只请求**当前页面同源**的相对路径：

```js
var KB_PATH = '/jsxsd/xskb/xskb_list.do';
fetch(KB_PATH, init)          // init: {method:'POST'|'GET', credentials:'same-origin', ...}
```

请求体逐字照搬上游（`jx0404id=&cj0701id=&zc=&demo=&xnxq01id=`），只把 `xnxq01id` 换成
**当前页面「学年学期」下拉框选中项**；读不到就退化成不带参数的 GET，让教务用它自己的默认学期。
相对路径保证请求落回用户当前所在的那台服务器：校外走网关映射域、校内直连教务，两条路都是同源。

### 本适配器请求的主机与路径（穷举）

| 主机 | 路径 | 方法 | 什么时候 | 数据去向 |
|---|---|---|---|---|
| **当前页面同源**（相对路径解析） | `/jsxsd/xskb/xskb_list.do` | POST（有学期号）/ GET（无学期号） | 仅当当前页面里没有解析到课表时 | 只带 `jx0404id/cj0701id/zc/demo/xnxq01id` 五个空值或学期号；**不带任何用户数据** |

除这一条以外，`extract.js` 里**没有第二个 `fetch`**，没有 XHR / WebSocket / EventSource /
`sendBeacon` / `new Image().src`；`parse.js` **一个网络调用都没有**（`grep -nE "fetch\(|XMLHttpRequest|document|window\."` 无输出）。
两份脚本里也没有任何可执行代码中的绝对 URL（`grep -nE "https?://" jw-adapters/ccit/*.js` 只命中注释）。

---

## 2. `allowHosts` 的取值与三条反直觉结论

```json
"allowHosts": ["*.webvpn.ccit.edu.cn"]
```

### 结论先行：本件选了**通配**，依据是「不依赖桥」+「网络闸门认通配」

课表页的主机（`http-10-198-47-148-8080.webvpn.ccit.edu.cn`）与登录域（`webvpn.ccit.edu.cn`）
**不是同一台主机**，所以白名单里必须能匹配到它，否则真机上请求会被拦掉。两条路可选，
本件选了**通配**，理由是逐条核实过宿主的两处匹配器**语义不同**：

| 用在哪 | 代码 | 认不认 `*.host` |
|---|---|---|
| **网络闸门**（提取期拦截子资源/导航/`fetch`/XHR/WS/beacon） | `JwWebViewStep.kt:101` `allows()` → `JwHostAllowlist.matches(host, allowedHosts)`；通配分支在 `JwHostAllowlist.kt:42-51` | **认**（`h == suffix` 或 `h.endsWith(".$suffix")` 都放行） |
| **桥注入**（`addWebMessageListener` 的 origin 规则） | `JwOriginRules.forAdapter()` `JwOriginRules.kt:22` `if (lower.startsWith("*.")) return@forEach` | **不认**（整项跳过） |

而本件是**纯 DOM 解析**：`extract.js` / `parse.js` 里不出现 `__ncOcr`、`__ncOcrGrid`、`__ncSelect`、
`__ncConfirm`、`__ncPrompt` 任何一个名字（`grep` 无输出），也不需要 `__ncCapabilities`。
**拿不到桥不影响任何功能**，所以选通配、让网络闸门放行网关下的全部映射域，是这里正确的取法；
改成写死某一个精确主机名反而更差（见 ②）。

反过来：如果哪天这个件要用 OCR 或提问桥，就必须改成**精确主机名**（`JwOriginRules` 只给精确项
发桥），那时要么接受只在校内直连域上有桥，要么改成 `startUrlPrompt` 型适配器。

**顺带核实的一条**：`JwWebViewStep.kt:409` 的 `gate.allowCurrentHost(url)`
**只在 `adapter.promptsForStartUrl`（即 manifest 写了 `startUrlPrompt`）时调用** ——
本件不是那种适配器，当前页面的域**不会**被自动补进白名单。所以这条通配是**必须的**，不是冗余。

### ① 代理域：放行它 = 放行它背后能转发到的全校服务 → 所以盯「要了哪条路径」

`webvpn.ccit.edu.cn` 是 WebVPN 网关，`*.webvpn.ccit.edu.cn` 覆盖它把任意内网服务重写成的子域。
这条通配在**网络层**确实很宽，所以本件的安全性不能靠域名归属来论证，只能靠**脚本要了哪条路径**：
本脚本只要 `/jsxsd/xskb/xskb_list.do` **一条**，且是相对路径 —— 它无法被改成去要别的路径，
路径是写死的常量、参数是五个固定键。这一点在 §5 第 3 条与 §6 第 9 条各签一次。

### ② 不把上游那条映射主机名写进白名单

`http-10-198-47-148-8080.webvpn.ccit.edu.cn` 是内网 `10.198.47.148:8080` 的一次映射结果，
把它写进 `allowHosts` 有两个问题：换个会话/换台服务器就失效（等于假装精确）；而且它是一条
**我们没有验证过**的具体主机授权。既然脚本走相对路径，网关下的任何一个映射域都不需要单独列出。

### ③ 通配 `*.` 拿不到 OCR / 提问桥 —— 本件不需要，接受这个代价

见上面的「结论先行」表：`JwOriginRules.kt:22` 把通配项整条跳过，所以 WebVPN 映射域页面上的
`__ncCapabilities.ocr` / `.ask` 是 `false`（桥不注入）；`JwHostAllowlist.matches()`
（`JwHostAllowlist.kt:42-51`）与 JS 沙箱 preamble（`JwScriptContract.kt:163` 的通配分支，
`JwScriptContract.buildPreamble`）都认 `*.` 后缀通配，**网络层照常放行**。
本件**不使用** OCR，也**不使用**提问桥，因此拿不到桥不影响任何功能 ——
选通配是**有意**的取舍，不是遗漏。

### ④ `loginUrl` 同源自动放行

`JwAdapterPackage.allowedHosts(loginHost)`（`JwAdapterPackage.kt:42-43`）会把 `webvpn.ccit.edu.cn`
本身加进白名单，所以用户停在门户首页时页面同源也是通的（虽然那条路径在门户上会 404，见 §8）。

---

## 3. 读取面（`extract.js` 到底读了页面上的什么）

穷举，全部读取点：

| # | 读的东西 | 明细 |
|---|---|---|
| 1 | 当前页面的课表表格 | `getElementById('kbtable')` / `'timetable'`，兜底找含 `.kbcontent` 的那张 `<table>`。取每行 `<tr>` 的 `<td>/<th>` 子元素、`colspan`/`rowspan` 属性、`className`（判断是不是 `kbcontent`）、`style`（判断 `display:none`）、`innerHTML`（课程明细原文）、`textContent`（表头星期标签、节次标签）。 |
| 2 | 页面的「学年学期」下拉框 | 遍历 `document.getElementsByTagName('select')`，只挑 `id` 或 `name` **含 `xnxq`** 的那个；只读每个 `<option>` 的 `value`（须匹配 `^\d{4}-\d{4}-\d$` 才认）、`text`、`selected`。 |
| 3 | 当前页地址 | `window.location.href` → 进载荷的 `pageUrl`，仅用于诊断（WebVPN 出问题时一眼看出是网关进的还是直连）。不含个人信息。 |
| 4 | 本机时间 | `new Date()` → 进载荷的 `now`（`YYYY-MM-DD`），用于推算开学日。 |
| 5 | 同源课表页（仅当 ① 没取到课表） | `response.text()` 的 HTML，交给 `DOMParser` 后按上面 1、2 两条再读一遍。 |

**不读**（逐项确认）：`localStorage` / `sessionStorage` / `document.cookie` / 任何表单控件的值 /
`input[type=password]` / 学号姓名 / 成绩 / 学籍 / 缴费 / 课表页以外的任何接口 / 任何跨域资源。
`grep -nE "localStorage|sessionStorage|document\.cookie|navigator\.|\.value" jw-adapters/ccit/extract.js`
只命中 `<option>` 的 `value`（上面第 2 条）。

---

## 4. 有没有绕过白名单的写法 —— 逐类点名

- **`eval` / `new Function`**：无。两份脚本里都不出现。
- **`fetch` / XHR**：`extract.js` 只有一处 `fetch(KB_PATH, init)`（相对路径）；`parse.js` 没有。
- **XHR / WebSocket / EventSource / `sendBeacon` / `new Image().src` / `<script>` 注入**：无。
- **写页面**：无。不 `appendChild`、不 `innerHTML =`、不 `document.write`、不改表单、不 `.click()`、
  不 `.submit()`、不注入任何全局函数（上游开头的 `window.validateYearInput = …` **已删除**）。
- **改地址 / 重定向 / Service Worker / `window.open`**：无。
- **定时器**：无 `setTimeout` / `setInterval`。
- **持久化**：无任何写入（本地或远端）。
- **凭据**：不读、不存、不上报账号密码或任何令牌；`credentials:'same-origin'` 只是让浏览器按
  同源规则带上教务自己的会话 Cookie —— 脚本本身看不到、也拿不到这些 Cookie。

---

## 5. 逐条（移植手册 §5 八条）

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | **不碰凭据** | ✅ 合格 | 登录全程由用户在 WebView 手工完成；脚本不出现 `password`/`pwd`/登录表单；不读 `localStorage`；`credentials:'same-origin'` 是浏览器按同源规则自动带 Cookie，脚本读不到 Cookie 值。不提问、不索要任何账号信息（本件根本不用提问桥）。 |
| 2 | **不外发** | ✅ 合格 | 全网只有一个请求目标：当前页面同源 + `/jsxsd/xskb/xskb_list.do`。没有第二个域，没有 XHR/WS/beacon/`new Image().src`，`parse.js` 无网络。 |
| 3 | **请求域可控** | ✅ 合格（写进 `allowHosts`） | `allowHosts: ["*.webvpn.ccit.edu.cn"]`。这是 WebVPN 代理域（§2①），宽是**代理本身**决定的；实际被请求的路径只有一条且写死，见 §1 表。 |
| 4 | **只读课表** | ✅ 合格 | 请求的就是课表接口 `/jsxsd/xskb/xskb_list.do`。不碰成绩/学籍/个人信息/缴费接口（上游脚本里也没有）。 |
| 5 | **不埋点** | ✅ 合格 | 无统计、无上报、无遥测；没有第三方 SDK，没有 `navigator.sendBeacon`。 |
| 6 | **不 eval 远程代码** | ✅ 合格 | 无 `eval` / `new Function`；取回来的 HTML 只用 `DOMParser` 解析，当数据读，不当代码跑。 |
| 7 | **不写页面** | ✅ 合格 | 见 §4「写页面」一行。上游那个往页面挂全局函数的 `validateYearInput` 已删。课表页没课时也不做任何「切页」点击（不猜、不代用户导航，改为报错让用户自己切）。 |
| 8 | **不依赖用户输入之外的秘密** | ✅ 合格 | 无硬编码密钥、无固定令牌、无他人学号。`xnxq01id` 取自用户当前所在页面的下拉框选中项。 |

---

## 6. 逐条（批次三专项检查表 10 条）

| # | 检查项 | 结论 | 依据 / 用例 |
|---|---|---|---|
| 1 | **周次四写法** | ✅ | `fixtures/week-forms` 一条一条钉住：`1-16周(单)`→`1-15 ODD`（高等数学A(一)）、`(单)1-16周`→`1-15 ODD`（线性代数）、`1-16(单周)`→`1-15 ODD`（概率论与数理统计）、`1-3,5-9周`→两条 `ALL`（离散数学：`1-3` + `5-9`）。另覆盖「单双被空白切开」的 `1-16周 双`→`2-16 EVEN`（计算机网络）。上游 `parseWeeks` 是 `weekStr.split('(')[0]`，前三种**都会塌成「每周都上」**、第二种更是一周都不剩 —— 变异测试 M1 把标记丢掉后该 fixture 立刻变红（§9.1）。**本轮补强（B6/B10）**：`fixtures/multi-spec` 另钉住两件事 —— ① 同一格里写了**两段周次**（两个 `font[title=周次(节次)]` 各一段、以及一段文本里并排两组 `1-8周[01-02节],10-16周[03-04节]`）时**逐段产出两条安排**，节次各按各的段走；② 区间分隔符是全角波浪 `～`(U+FF5E)、全角减号 `－`(U+FF0D)、数学减号 `−`(U+2212)、en dash `–`(U+2013) 时与半角标准写法 `1-16周` **结果一致**（都读成 1-16 周）。这两处的原缺陷都是**静默丢数据**：只取第一段 → 第二段整段消失（连 `warnings` 都没有）；分隔符不归一 → 掉进「单数字回退」把 16 周读成只上第 1 周。 |
| 2 | **括号内纯数字不是周次** | ✅ | `(1)1-16周`→`ALL 1-16`（数据结构）：`WEEK_PAREN` 只认「周/週」，`SERIAL_PAREN` 认纯数字与数字区间（含 `(1-2)`、`(1,2)` 形态），两种情况都在 `weeksIn` 里整段抹掉、不当周次；`(周)` 仍按周字处理。 |
| 3 | **无星期表头兜底** | ✅ | **禁止按数组长度猜列**已落实：`extract.js` 交出网格列号 `col` 与跨列数 `span`（`colspan`/`rowspan` 都算进去），`parse.js` 用 `cellAtGridCol(row, want)` 按列号取格子；兜底窗口起点是**表宽**（`tableWidth - 7`）而不是某一行的格子数。`fixtures/no-header` 就是这个场景：首列 `节次` 用 rowspan 跨两行，被跨掉的行少了 col 0 —— 按数组下标算会整行前移一天。拿不到列号（无 `col` 字段）时退回下标，且**产出的列撞车/越界一律不猜**（`dayColOf[day] = -1`，那些天的课不导入并进 `warnings`，见 `unplacedDays`）。 |
| 4 | **时间合法性** | ✅ | `periodTimes` 两个来源：内置 12 节（上游 `saveAppTimeSlots()` 逐条照搬，全部 `HH:mm`、`00:00–23:59`）+ 超出 12 节时的占位条目，写成 `pad2(7 + 节次)`。解析阶段把节次夹在 `MAX_PERIOD = 16`：第 16 节 = `23:00-23:45` 是最后一个当天档位，**越界的块按「读不出节次」跳过并点名**（`overPeriodBlocks`），绝不会产出 `24:00`/`85:45`。**用例**：`fixtures/period-limits` —— 第 13-14 节与第 15-16 节（正好压在 `MAX_PERIOD` 边界上）各收下并补出占位时刻，`[17-18节]`、`[19节]`、`[1718节]`（连写脏数据）三种越界形态各跳过并点名，另有一门只用内置作息表的第 11-12 节作对照（证明内置那 12 条没被改坏）。**变异**（§9.4）：把 `if (periods.end > MAX_PERIOD) {` 改成 `if (false) {` → 该用例变红，越界课被收下、学期总节次涨到 19，输出里真的出现 `24:00`/`25:00`/`26:00`（正是会被载荷 `TIME_REGEX` 拒收、整包白导的那个形状）；把 `pad2(7 + extra)` 改成 `pad2(9 + extra)` → 该用例变红，首个占位时刻变成 `24:00`。**这两次变异下另外三份 fixture 全绿**（它们的节次最大只到 6 节，`MAX_PERIOD` 夹取与占位循环一行都没跑到）—— 也就是说这条结论此前**没有任何用例守着**，现在由 `period-limits` 独力钉住。 |
| 5 | **`warnings` 上限** | ✅ | 所有提示统一走 `pushWarning()`：每条按码位截断到 `MAX_WARNING_TEXT = 200` 字（截断处补 `…`，不劈开代理对），总数 ≤ `MAX_WARNINGS = 20`，重复文本去重。任何调用点都绕过不了这两个上限。 |
| 6 | **分页** | ✅ 不适用（并已在 `warnings` 里兜底） | 本接口**一次性返回整学期课表**，没有分页参数、没有总数、没有「取下一页」。上游也只发一次请求、不翻页。若教务改成截断返回，表现是「某些格子没有课」而不是报错 —— 这一条是已知不确定，列在 §8。 |
| 7 | **学期名** | ✅ | 优先用教务给的：`term.name`（下拉框 option 文字）→ `termNameFromCode(term.code)`（`2026-2027-1` → `2026-2027学年第一学期`）。两者都拿不到时用 **`长春工程学院` + 按提取时刻推的学年学期**，并 push 一条 `warnings` 说明这是推的、请改名。**没有用适配器名当学期名**。 |
| 8 | **`teacher` 拿不到就留空** | ✅ | `teacherOf()` 认不出教师 font 就 `return null`；上游写的「未知教师」「未知」会被归一成 `null`。`basic` fixture 的「大学物理（一）」就是这种（`"teacher": null`）；`location` 同理（`roomOf()`）。 |
| 9 | **`allowHosts`** | ✅ | WebVPN 学校：请求**当前页同源相对路径**，主机名一个都没写死（§1）。课表页主机（`http-10-198-47-148-8080.webvpn.ccit.edu.cn`）与登录域不同，所以白名单必须能匹配到它 —— 本件选**通配 `*.webvpn.ccit.edu.cn`**，依据是「网络闸门认通配（`JwWebViewStep.kt:101` → `JwHostAllowlist.matches`）、桥注入不认通配（`JwOriginRules.kt:22`），而本件是纯 DOM 解析、不用桥」，逐条论证见 §2「结论先行」。非默认端口的问题本件不涉及（网关在 443/80）。 |
| 10 | **变异测试** | ✅ | 见 §9：两轮共九处故意改坏（移植时 M1–M3 + 本轮修复 M4–M9），各自红了对应的用例；源码用 sha256 前后比对确认未被改动。 |

---

## 7. 移植时删掉 / 改掉的上游行为

| 上游 | 本适配器 | 为什么 |
|---|---|---|
| `showAlert` 开场确认、`showToast`、`notifyTaskCompletion` | 全部删除 | 空课的导入流程自己会确认与反馈；适配器只负责交数据。 |
| `window.validateYearInput = function …` + `showPrompt` 手输起始学年 + `showSingleSelection` 选第一/第二学期 | 删除；改为读课表页「学年学期」下拉框的**选中项** | 移植手册 §3 第 1 步：能用页面解决的别弹窗 —— 用户此刻就停在教务页面上，他看得见自己选的是哪一学期。读不到时不拼参数，让教务用自己的默认学期。 |
| `saveAppConfig()`（写死 `semesterTotalWeeks: 20` / `firstDayOfWeek: 1`） | 取 `20` 作 `DEFAULT_TOTAL_WEEKS`，`firstDayOfWeek: 1` 体现为「`firstDay` 取最近的周一」（§4.3）；开学日**推算值进 `warnings`** | 教务给不出开学日与总周数，只能推算/假定；推算值在库里和真值长得一样，必须出声。 |
| `saveAppTimeSlots()`（12 节硬编码） | 照搬进 `PERIOD_TIMES` | 同上，并进 `warnings`。 |
| `applyCustomTimeSplitting()`（按教室给错峰课改上下课时间） | **保留判定，不保留改写**：地点命中上游那两组正则的课程计数并进 `warnings` | 移植手册 §4.4 前半条：有 `startSection`/`endSection` 时按节次放，自定义时间作为附加信息说明即可。我们的载荷只有**一份学期级作息表**，装不下每门课各自的时刻 —— 静默丢掉是不诚实的，所以点名。 |
| `mergeAndDistinctCourses()`（按课名排序 + 相邻节次合并成一块） | 不合并、不排序 | ① 排序由应用自己按节次做；② 合并会把「同一门课同一天相邻的两段」融成一块，边界是脚本猜的 —— 保留教务给的原始边界更不容易错。视觉上两者都是连续的一段。 |
| `parseTimetableToModel()` 里 `cells.forEach((cell, dayIndex) => day = dayIndex + 1)` | 改为**网格列号 → 星期** | 上游按「第几个 td 就是星期几」算。强智课表首列是「节次」列且常 `rowspan` 跨行，被跨掉的那一行第一个 td 落在 col 1 —— 按数组下标算会把整行错一天。 |
| `parseWeeks()` 的 `weekStr.split('(')[0]` | 改为分段自判单双（见 §6 第 1 条） | 上游把单双标记整段丢掉。 |
| 节次 `sectionPart[1].split('-').map(Number)` | 改为认逗号/顿号/连写（`[05,06节]`、`[0304节]`），并夹 `MAX_PERIOD` | 上游遇到逗号写法会读出 `NaN` 而把整块静默丢弃；不夹上限会产出非法时刻导致整包被拒。 |
| 空值兜底 `|| "未知教师"` / `|| "未知地点"` | 归一成 `null` | 移植手册 §4.7：空着比写「未知」好（「未知」会当成真名显示）。 |
| `weekStr` 取不到就 `startSection = 0` → 静默丢弃 | 计数 + 点名进 `warnings`；一个块都没解析出来时直接报错并列出课名 | 移植手册 §4「不许静默丢课」。 |

---

## 8. 已知不确定（交给真机抽验与用户反馈）

1. **真实 DOM 形状没验过**。fixtures 是**合成**的（按上游选择器 + 强智通行结构编造），
   只保证「同样的输入永远得到同样的输出」，**不保证真实页面上解析一定对**。
   拿到真实 dump 请替换 `fixtures/` 并重跑门。
2. **`div.kbcontent` 的取法**。上游取 `div.kbcontent`；本件先按类名**整体等于** `kbcontent` 选
   （避免把 `kbcontent1` 也当一份），一份都没有时退回「类名含 kbcontent」，多个时优先取
   `display:none` 的那份。如果该校页面的可见简写恰好叫 `kbcontent`、完整版叫别的名字，会取到简写
   （课名/教师/教室仍能读出来，周次节次可能缺 → 会进 `warnings`，不会静默）。
3. **课表页的 POST 是否需要额外参数**。请求体逐字照搬上游（`jx0404id/cj0701id/zc/demo/xnxq01id`）。
   上游作者显然是在真实页面上试出来的；我们无法验证。若教务升级加了校验，表现为接口返回空表 →
   报错提示用户（不会静默出空课表）。
4. **WebVPN 路径重写**。本件假设网关只改主机名、不改路径（上游那条 URL 的路径段是原样的
   `/jsxsd/xskb/xskb_list.do`，与这个假设一致）。若某些网关版本会加 `/http/<token>/` 前缀，
   相对路径会 404 → 报错。真机抽验时看 `pageUrl` 就能确认。
5. **停在本站门户首页**。用户若停在 `webvpn.ccit.edu.cn` 门户（没进教务），相对路径请求会落到
   门户上而非教务，表现为非 2xx 或空表 → 报错并提示「请先打开课表查询」。
6. **错峰作息的正则**：照搬上游，其中第二组的 `(DS)|(XS)` 相当宽（任何地点里出现这两个字母都命中）。
   它只影响**一条 `warnings`**（不会改数据），但可能在个别学校产生误报。
7. **「第 16 节」上限**：`MAX_PERIOD = 16` 是「合法作息不会到 17 节以上」的判断，不是教务的约定。
   若真有第 17 节的课，会被当成脏数据跳过并点名（宁可报出来，也不让整包因非法时刻被拒）。
   这条界线**现在有用例**：`fixtures/period-limits` 的 `[15-16节]` 收下、`[17-18节]`/`[19节]`/
   `[1718节]` 跳过，变异 M8 一放开夹取该用例就红（§9.4）。

---

## 9. 变异测试记录（2026-09-13）

用 Node `vm` 载入 `parse.js`，在内存里对源码做字符串替换（**不落盘**），五份 fixture 各跑一遍：

```
basic  week-forms  no-header  multi-spec  period-limits
```

分两轮：**移植时**（§9.1）与**对抗式验收证伪后的修复**（§9.2–§9.4）。所有替换都只在内存里做，
跑完重新读文件算 sha256，与跑之前比对（§9.5）。

### 9.1 移植时（M1–M3，在 2026-09-13 抗验之前的 revision 上测得）

当时的三份 fixture（`basic`/`week-forms`/`no-header`）下的结果：

| # | 改坏的地方 | 结果 |
|---|---|---|
| M1 | `oddOnly` 恒 `false`（丢掉单双标记） | `basic`/`week-forms`/`no-header` 全 DIFF |
| M2 | `colOf` 直接返回数组下标（网格列号 → 下标） | `basic`/`week-forms`/`no-header` 全 DIFF |
| M3 | `fallbackStart` 恒 `0`（无表头兜底窗口从第 1 列起） | 只有 `no-header` DIFF |

**本轮修复后在当前 revision 上重跑，三条结论逐条不变**（M1/M2 仍三红、M3 仍只有 `no-header` 红；
`multi-spec`/`period-limits` 在这三处变异下照旧 MATCH —— 它们守的是别的路径）。

### 9.2 B6：同一格多段周次（M4–M6）

**缺陷**：`specOf()` 命中第一个带「周次」的 `font` 就 `return`，同一个块里的第二段周次整段消失。
两种形态各改坏一次：

| # | 改坏的地方 | 变红的用例 | 红在哪 |
|---|---|---|---|
| M4 | `specOf` 只返回第一段：`if (out.length) return out;` → `return [out[0]];` | `multi-spec` | `courses[0].blocks` 长度 2→1、`courses[1].blocks` 长度 2→1（两门课的第二段安排没了） |
| M5 | `specOf` 读完第一个 `font` 就 `break`（形态①：两个 `font` 各一段） | `multi-spec` | `courses[0].blocks` 长度 2→1（`多段周次课` 只剩 `w1-8`，`w10-16` 消失） |
| M6 | `pushSpecs` 只产出第一对（形态②：一段文本里并排两组） | `multi-spec` | `courses[1].blocks` 长度 2→1（`单段两组课` 只剩 `w1-8`） |

三次变异下 `basic`/`week-forms`/`no-header`/`period-limits` **全绿** —— 老用例盖不住这条路径。

### 9.3 B10：区间分隔符（M7）

**缺陷**：区间分隔符的字符类只收 `[-—~至]`，全角波浪 `～`(U+FF5E)、全角减号 `－`(U+FF0D)、
数学减号 `−`(U+2212)、en dash `–`(U+2013) 都读不成区间，掉进「单数字回退」→ 整门课静默塌成
只上第 1 周（无告警）。

| # | 改坏的地方 | 变红的用例 | 红在哪 |
|---|---|---|---|
| M7 | `RANGE_SEP` 退回只认半角：`/[～－−–—~至到]/g` → `/[-]/g` | `multi-spec` | `courses[2..5].blocks[0].endWeek` 16→1（`全角波浪课`/`全角减号课`/`数学减号课`/`短横课` 四门全部塌成 `w1-1`）；`courses[6]`（标准半角写法）不受影响 |

### 9.4 B14：`MAX_PERIOD` 夹取与占位作息公式（M8–M9）

**缺陷**：这两处此前**零用例覆盖** —— `basic` 的节次最大到 6、`week-forms`/`no-header` 到 4，
`MAX_PERIOD = 16` 的夹取与占位作息那层循环压根没跑过（`AUDIT.md` §6 第 4 条的 ✅ 当时没有用例支撑）。

| # | 改坏的地方 | 变红的用例 | 红在哪 |
|---|---|---|---|
| M8 | `if (periods.end > MAX_PERIOD) {` → `if (false) {` | `period-limits` | 三条告警少一条（`warnings` 4→3），占位区间从「第 13-16 节」变「第 13-19 节」，`periodTimes` 16→19 条，越界课被收下 —— 且补出的 `24:00`/`25:00`/`26:00` 不匹配载荷的 `TIME_REGEX`，真机上表现为**整次导入被拒** |
| M9 | `pad2(7 + extra)` → `pad2(9 + extra)` | `period-limits` | 首个占位时刻 `20:00`→`22:00`（第 13 节），第 15 节起变成 `24:00`，警告里的「已补上占位时间（20:00 起）」也一起变 |

两次变异下 `basic`/`week-forms`/`no-header`/`multi-spec` **全绿**。

### 9.5 还原确认

```
sha256(parse.js) BEFORE = a8b28bbc816dc13f9e621a12354e2b7c5c14cc7b4dc7b40371cf7bf4401a2308
原始         {"basic":"MATCH","week-forms":"MATCH","no-header":"MATCH","multi-spec":"MATCH","period-limits":"MATCH"}
M4..M9 见上（每次都只红对应的那一条）
sha256(parse.js) AFTER  = a8b28bbc816dc13f9e621a12354e2b7c5c14cc7b4dc7b40371cf7bf4401a2308
源码未被改动: true
```

六次替换全部在内存里做，跑完重新读文件，sha256 与跑之前**逐字节一致**（见上面的 BEFORE/AFTER 行）。

---

## 10. 本轮修复记录（2026-09-13 对抗式验收证伪之后）

验收阶段实际跑出了三处缺陷，全部在 `parse.js` 内修掉，并且**每处都补了能拦住它的用例**
（`fixtures/multi-spec`、`fixtures/period-limits`，期望值按规范手推、不是把 `parse.js` 的输出贴进去）。
批次三复核又追加 `B15`（见表末）：`weeksIn()` 的「先全文删空白」会把空白分隔的周次段并掉，用
`fixtures/weeks-space-segments` 钉住（变异复现「只剩 1-3」）。

| # | 缺陷（证伪者已复现） | 修法 |
|---|---|---|
| **B6** | `specOf()` 命中第一个「周次」`font` 就 `return`：同一格里有**两段周次**时，第二段整段静默消失（`1-8周[01-02节]` + `10-16周[03-04节]` 只产出前一段，`warnings` 里连一条相关记录都没有）。`1-8周[01-02节],10-16周[03-04节]`（一段文本里两组）同样只剩前一组 | `specOf()` 改为**逐段**收集，返回数组（与同平台 `hnie` 的 `specsOf` 同口径）：① 遍历**所有**带「周次」的 `font`，不再命中即 return；② 新增 `pushSpecs()`，用带 `/g` 的方括号正则把一段文本**逐对**切开（`weeks` 取其前、`periods` 取括号内）。调用点改为按段循环，**节次各按各的段走**；错峰地点的计数改成按**块**算（`imported` 标志），不按段重复计 |
| **B10** | 区间分隔符的字符类只收 `[-—~至]`：`1～16周`(U+FF5E)、`1－16周`(U+FF0D)、`1−16周`(U+2212)、`1–16周`(U+2013) 都读不成区间，掉进「单数字回退」→ 静默变成**只上第 1 周**（`w1-1`，16 周丢 15 周，无告警） | 新增 `RANGE_SEP = /[～－−–—~至到]/g`：`weeksIn()` **把分隔符统一成半角连字符**，再取区间；`periodsIn()` 用同一个常量做同样归一（`[01～02节]` 同理）。标准形态 `1-16周` 归一后原样不变。取区间的正则同时抽成 `WEEK_RANGE` 常量，避免字符类两处各写一份、日后改一处漏一处 |
| **B15** | **「先全文删空白」吞段**（B10 当年的修法带进来的副作用）：`weeksIn()` 里 `replace(/\s+/g,'')` 把**空白本身也当成了要删的东西**，可空白在本域里是**分段符**（`1-3周 5-9周`）—— 删完拼成 `1-3周5-9周`，`WEEK_RANGE` 只吃到 `1-3`，**5-9 整段静默消失**（一声不吭） | 删掉 `replace(/\s+/g,'')` 那一行，改按统一口径：① 分隔符归一后**再吃掉区间两端的空白**（`replace(/(\d)\s*-\s*(\d)/g, '$1-$2')`，保住 `1 - 16 周` 这种区间被空白切开的形态）；② 剩下的空白留给 `SEGMENT_SPLIT`（`/[\s,，、;；]+/`，本来就收 `\s`）当分段符。用例 `fixtures/weeks-space-segments`：一格周次写成 `1-3周 5-9周`，期望值按规范手推为 **1-3 与 5-9 两个 ALL 块**（另带一格 `[05～06节]` 守 B10 的节次归一）；变异（把全删空白加回去）**只有这条变红** |
| **B14** | 覆盖缺口：`MAX_PERIOD` 的夹取与占位作息的 `pad2(7 + extra)` 公式**零用例**。把 `if (periods.end > MAX_PERIOD)` 改成 `if (false)`、把 `pad2(7 + extra)` 改成 `pad2(9 + extra)`，两次变异下三份 fixture 全绿 | 代码没改（本来就是对的），**补用例**：`fixtures/period-limits` 把节次构造到 13-16（补占位、正好压 `MAX_PERIOD` 边界）与 17/19/连写三形态（越界跳过并点名），并把这两处变异各跑一次（§9.4）；`AUDIT.md` §6 第 4 条的 ✅ 现在有用例支撑 |

修复过程中**没有改动任何期望值**：三份老 fixture 的期望值一字未动，修完仍然全 MATCH；新用例的
期望值先按规范手推、再跑，只有 `period-limits` 的跳过告警文本推错了两处（漏了一个空格、漏了
「结构」二字），**以源码里的固定模板为准改正期望值**，代码未动（§9.4 的两次变异证明这段文本确实被用例看着）。

---

## 11. 出处与签名

- 上游：`shiguang_warehouse` / `resources/CCIT/ccit.js`（MIT，作者 **星河欲转**），
  commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，提交时间 2026-09-12 12:50:29 +0800。
  上游社区公约要求保留贡献者记录 —— `manifest.json` 的 `author` 与两份脚本的文件头都写了出处。
- 本适配器的全部逻辑由我逐行读过并重写为 ES5 两段式；`extract.js` 只取数（不碰 DOM 之外的任何
  页面状态、不发第二个请求），`parse.js` 是纯函数（无网络、无 DOM、无全局读写）。
- 移植者：**0x7E-2023**　日期：**2026-09-13**
