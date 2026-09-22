# 安全审计 —— 河南科技大学教务适配器（`haust`）

审计对象：`jw-adapters/haust/extract.js` + `parse.js`
（移植自拾光社区 `HAUST/haust.js`，上游作者 **Haooz**，MIT）

- **平台**：树维 EAMS（接口路径 `/eams/dataQuery.action`）。判据是**脚本实际请求的接口路径**，
  不是上游注释 —— 不过这一件上游自己也写着「新版树维教务」。树维 = 上海树维信息科技有限公司
  （SupWisdom，新开普子公司），与 `/jsxsd/` 的强智科技**不是一套**。**不要写「强智」。**
- **入口**：`https://vpn.haust.edu.cn` —— 上游 `adapters.yaml` 的 `import_url`，
  描述是「需通过VPN访问，登录后进入课表页面点击导入」。
- **上游快照**：`e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）。
- **移植者**：0x7E7-2023　**日期**：2026-09-17

---

## 1. 这一件的形态（与同批其余件的区别）

本批 12 件里，`haust` 是**唯一**的「接口取学期 + DOM 读课程」混合体：

| | 同批 `hpu`/`uestc`/… 那 10 件 | 本件 `haust` |
|---|---|---|
| 接口 | `dataQuery.action` 取学期 + `courseTableForStd.action` 取课表 | **只有** `dataQuery.action` 一条 |
| 课程来源 | 课表 HTML 里内嵌的 `TaskActivity` 块 | **已经渲染出来的课表页 DOM** |
| 周次 | 位图字符串（`1010…`） | **文本**（`1-16周` / `单3-17`） |

所以：`parse.js` 里**没有** `TaskActivity` 解析，**没有** `index = 星期 * unitCount + 节次`
的寻址，**没有**位图口径 —— 见 §6。

---

## 2. `eval` 的移除（本件的核心改动）

### 上游怎么写的

上游 `HAUST/haust.js` 第 31–32 行（`getSemesterList()` 里）：

```js
var text = await response.text();
var data = eval("(" + text + ")");
```

`text` 是 `POST /eams/dataQuery.action` 的**响应正文**，来自网络。这一条命中移植手册 §5 第 6 条
「不 eval 远程代码」—— 内置适配器的硬门槛。上游用 `eval` 而不是 `JSON.parse` 有它的道理：
树维这个接口回的是 **JavaScript 对象字面量**而不是严格 JSON（键名可能不加引号、可能带尾逗号、
偶尔带单引号），`JSON.parse` 会直接抛。

### 改成了什么

```js
function parseJsonText(raw) {
    try {
        return { ok: true, value: JSON.parse(text(raw)) };   // ① 先按严格 JSON
    } catch (e) { /* 不是严格 JSON，落到第二步 */ }
    try {
        return { ok: true, value: looseJson(raw) };           // ② 容错扫描器
    } catch (e2) {
        return { ok: false, value: null };                    // ③ 两条都读不了就如实说
    }
}
```

`looseJson` 是**自己写的一个只读数据的扫描器**（不构造任何函数、不调用 `eval` / `new Function` /
`setTimeout("字符串")`）：按字符推进，能吃掉五种 `eval` 当年顺手吃掉的写法 —— 包在外面的圆括号
（`({"…":…})`，`eval` 时代最常见的 JSONP 式包装）、单引号字符串、无引号键名、尾逗号、
`//` 与 `/* */` 注释；只认 `true/false/null/undefined` 与数字（其余一律当字符串），
**不认任何表达式、函数调用、运算符**（`alert(1)` 会被整段读成一个字符串值，而不是执行）。

也就是说：即使有人把 `alert(1)` 塞进响应里，本适配器也只会把它读成一个字符串 `"alert(1)"`，
**不会执行**。这是与 `eval` 的本质区别。实测（临时脚本，未落仓库）：

```
输入 ({"semesters":{"y2026":[]},"ok":true,})              → 解析成对象 {semesters:{y2026:[]}, ok:true}
输入 {'semesters':{'y2026':[{...}]}}                      → 解析成对象（单引号 + 无引号键都吃下）
输入 /* c */ {semesters:{y2026:[{...}]}, // 注释\n n:3,}  → 解析成对象（注释 + 尾逗号都吃下）
输入 alert(1); semesters = {"y2026":[…]}
     → 解析成**字符串** "alert(1);"（包一层圆括号的那段），整段没有被执行
```

③ 那条不是摆设：`extract.js` 把 `semesterError` 与响应前 200 字原样交出去，
`parse.js` 会把它写进 `warnings`（「学期列表没有取到（…），学期名与开学日已按导入日期推算」），
课表照样导得进来。**不会静默失败，也不会因为学期接口抽风就丢整个课表。**

### 自检

```bash
$ grep -nE "eval\(|new Function|setTimeout\(\"|setInterval\(\"" jw-adapters/haust/*.js
（无输出：eval / new Function 这两个名字在**注释里**才出现，见下）

$ grep -n "eval" jw-adapters/haust/extract.js
16:    //   ① 【安全】上游把接口返回的正文丢进 eval（「eval ( "(" + text + ")" )」）来解析，
19:    //      全程不构造任何可执行代码，也不调用 eval / new Function（AUDIT.md §2 有专节）。
```

也就是说：**代码里没有 `eval` / `new Function`，只有注释在说明上游当年用的是什么、我们换成了什么**
（这一句是给下一位读者看的，删掉它反而更容易被人偷偷加回来）。

上游另一处相关写法 `setTimeout(tick, POLL_MS)`（`masu` 那种轮询）本件没有；本件唯一的
`setTimeout` 是 `extract.js` 里给学期请求加的 10 秒超时兜底，**接收的是函数**，不是字符串。

---

## 3. 为什么 `allowHosts` 是空数组

### 3.1 上游的 URL 情况

```bash
$ grep -oE "https?://[A-Za-z0-9._-]+" resources/HAUST/haust.js | sort -u
（无输出）

$ grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|eval\(|new Function" resources/HAUST/haust.js
20:        var response = await fetch("/eams/dataQuery.action", {
32:        var data = eval("(" + text + ")");
```

**上游全文没有任何绝对 URL**，唯一一次 `fetch` 用的是**相对路径** `/eams/dataQuery.action`。
上游 `adapters.yaml` 里的 `https://vpn.haust.edu.cn` 是**入口**（VPN 门户），不是接口域。
**教务系统的真实主机名在上游脚本里从未出现** —— 我们拿不到，也不去猜。（不编一个主机名写进白名单：
写错的精确主机名比空数组更危险，因为它看起来像「已经核对过」。）

### 3.2 本适配器怎么请求

```js
var TERM_PATH = '/eams/dataQuery.action';
fetch(TERM_PATH, { method: 'POST', credentials: 'include', … })
```

**只发这一条同源相对路径请求**：浏览器把它解析到**当前页面的主机**上。用户此刻停在教务课表页
（`vpn.haust.edu.cn` 网关背后、或者校内直连的教务域），请求就落在那一台上，天然同源 ——
白名单闸门认 `loginUrl` 的主机＋`allowHosts`，同源永远放行。

因此 `manifest.json` 的 `allowHosts` 为 **空数组**，理由与同批的 WebVPN 学校（`ccit`）同一条：
**脚本只请求当前页面同源，主机名一个都没写死。**

### 3.3 一个必须说清楚的已知边界

上游给的是 **VPN 入口**（`vpn.haust.edu.cn`），而教务系统本身很可能在**另一个域**上
（上游脚本没告诉我们那个域叫什么）。宿主的白名单闸门认的是 `hostOf(loginUrl)` +
`manifest.allowHosts`。于是：

- 如果真机上用户停的课表页**就在 `vpn.haust.edu.cn` 这个源**（VPN 网关整站代理、
  或网关把内网地址重写成 `xxx.vpn.haust.edu.cn` 子域）→ 相对路径请求同源，**闸门放行**，
  一切正常；
- 如果课表页落在**另一个域**（例：`jw.haust.edu.cn`，只是通过 VPN 拨号访问）→
  相对路径请求打到那个域上，**不在白名单里、会被闸门拦下**。

届时的修法只有一步，`AUDIT.md` 写在这里方便真机核对时照做：
**把课表页的实际主机名加到 `manifest.allowHosts`（精确主机名，不要通配）**。
本适配器不需要改任何代码 —— 它本来就发相对路径。

**我们没有真机账号，无法确认走的是哪一条**，所以这一条作为**已知不确定**如实列出，
不靠猜一个主机名把它糊过去。

---

## 4. 请求了哪些域（穷举）

| 主机 | 路径 | 方法 | 什么时候 | 带什么 |
|---|---|---|---|---|
| **当前页面同源**（相对路径解析） | `/eams/dataQuery.action` | POST | 每次提取，一次 | 固定 body `dataType=semesterCalendar&tagId=semesterBar&empty=true`；`credentials:'include'` 让浏览器按同源规则带教务自己的会话 Cookie |

- 除这一条以外，`extract.js` 里**没有第二个 `fetch`**，没有 XHR / WebSocket / EventSource /
  `sendBeacon` / `new Image().src` / `<script>` 注入。
- `parse.js` **一个网络调用都没有**，也没有 `document` / `window` / `localStorage` 的读写。
- 两份脚本的可执行代码里**没有绝对 URL**（`grep -nE "https?://"` 只命中文件头注释里的上游仓库链接
  与平台说明）；`fixtures/*.extracted.json` 里的 `https://jw.haust.edu.cn/...` 是**回归用例的输入数据**
  （合成的示例页面地址），不是脚本发出的请求。
- 网络失败不阻断导入：请求失败 / 超时（10 秒）/ 返回非 2xx / 不是 JSON，一律降级成
  「学期列表为空 + `semesterError` 说明」，课表照常从 DOM 读出来。

---

## 5. 读取面（`extract.js` 到底读了页面上的什么）

| # | 读的东西 | 明细 |
|---|---|---|
| 1 | 课表格子 | 在当前文档、`iframe` 的文档、以及 iframe 里的 iframe 文档里找 `td[title]`、`td[id^='TD']`、`td.infoTitle`；取每个格子的 `title`、`textContent`（压掉空白）、`id`、`className`、`rowSpan`，以及它在表格里的行列位置（用兄弟节点数出来，不用 `row.cells`） |
| 2 | 课表表头 | 同样这三个文档里的 `td` / `th` 的文字：只取「星期X」与「第N-M节 HH:mm-HH:mm」（两段必须相邻）两类，分别给出「哪一列是星期几」与「第 N 节的上下课时间」。表头只写「第N节」不带时间的，也照实交出去（`start` / `end` 为空串），由 `parse.js` 决定怎么处理并出声 |
| 3 | 学期接口响应 | `data.semesters` 下的学期数组，每个学期只带走 `schoolYear` / `name` / `startDate` / `endDate` 四个字段；接口里其余字段（学分制、小节数等）一律不带出去 |
| 4 | 页面元信息 | `document.title`、`window.location.href`、取数当天的日期 |

**不读**（逐项确认）：`localStorage` / `sessionStorage` / `document.cookie` / 任何表单控件的值 /
`input[type=password]` / 学号 / 姓名 / 成绩 / 学籍 / 缴费 / 课表页以外的任何接口 / 任何跨域资源。

上游有、本件**删掉**的东西：`window.shiguangBridge.showToast` / `notifyTaskCompletion`（上游的推送式
交付）、`showAlert` 开场说明弹窗、`showSingleSelection` 选学期弹窗、以及上游往 `console.log` 打
「HAUST调试: …」（把课表格子内容写进控制台）。本件**一次 `console.log` 都没有**。

---

## 6. 本件不涉及位图基准（周次是文本）

批次四的检查表第 1 条（「位图下标 `i` 就是第 `i` 周，下标 0 是占位符」）**对本件不适用**：

- 同批 10 件的周次来自课表 HTML 里内嵌的 `TaskActivity` 参数，形态是**位图字符串**（`101010…`），
  所以才要争「第 0 位是什么」；
- **本件的周次是文本**：格子的 `title` 里写的就是 `1-16周` / `单3-17` / `双2-8` / `1-3,5-9周`，
  上游 `parseWeeks(weekStr)` 逐段解析，**没有任何位图、没有任何下标换算**；
- 因此**没有「第 0 周」这个风险**（本适配器从文本里读出的周次一律 `>= 1`，`0` 段直接算读不出来
  并计数进 `warnings`）。

**这不等于本件不受周次口径约束** —— 本件自己的周次口径是：

> 逐段判单双（`单`/`双` 前缀、或写在段内的 `单`/`双` 字），区间 `start-end` 展开成显式周次，
> 排序去重后按手册 §4.1 折成极大段；**标了单/双的段只产出一个块**（`ODD` / `EVEN`，
> 范围取周次的最小到最大），未标单双的连续段产 `ALL`。

`fixtures/weeks-text` 就是钉这一条的用例（四种写法各一条 + 空格分隔的单双标记 + 空格分隔的多段）。

---

## 7. 逐条（移植手册 §5 八条）

| # | 检查项 | 结论 | 依据 |
|---|---|---|---|
| 1 | **不碰凭据** | ✅ 合格 | 登录全程由用户在 WebView 手工完成；脚本不出现 `password`/`pwd`/登录表单；不读 `localStorage`；`credentials:'include'` 是浏览器按同源规则自动带 Cookie，脚本**读不到** Cookie 值。不使用提问桥（上游的 `showAlert` / `showSingleSelection` 已删除），因此不存在弹窗索要账号信息的可能。 |
| 2 | **不外发** | ✅ 合格 | 全网只有一个请求目标：当前页面同源 + `/eams/dataQuery.action`。没有第二个域、没有 XHR/WS/beacon/`new Image().src`；`parse.js` 无网络。 |
| 3 | **请求域可控** | ✅ 合格 | `allowHosts: []`，相对路径 + `loginUrl`（`https://vpn.haust.edu.cn`）同源。**没有通配**。上游无绝对 URL（§3.1），因此没有「上游写了但我们没审」的域。边界见 §3.3。 |
| 4 | **只读课表** | ✅ 合格 | 请求的 `dataQuery.action` 只取学期列表（`dataType=semesterCalendar`），读的 DOM 只有课表页。不碰成绩 / 学籍 / 个人信息 / 缴费。上游也只请求这一个接口。 |
| 5 | **不埋点** | ✅ 合格 | 没有统计、上报、遥测；没有第三方 SDK；没有 `console.log`（把上游那两处调试输出删了）。 |
| 6 | **不 eval 远程代码** | ✅ 合格 | **上游有 `eval`，已删除**；见 §2。全文现在没有 `eval` / `new Function` / 字符串形式的 `setTimeout`。响应正文只用 `JSON.parse` 或自写的**只读**扫描器处理。 |
| 7 | **不写页面** | ✅ 合格 | 只读 DOM：`querySelectorAll` / `getAttribute` / `textContent` / `id` / `className` / `rowSpan` / `parentNode` / `previousSibling`。**不写 DOM、不改表单、不触发提交、不点击、不注入全局函数**。跨域 iframe 的 `contentDocument` 抛异常时直接跳过（try/catch 里只 `return null`），不尝试任何绕过。 |
| 8 | **不依赖用户输入之外的秘密** | ✅ 合格 | 没有硬编码密钥、令牌、他人学号；脚本里没有任何真实账号数据。学期是从教务自己的列表里**自动挑的**（按 `today` 落在起止日期里），不问用户、也不写死某一个学期 id。 |

---

## 8. 逐条（批次四专项检查表 12 条）

| # | 检查项 | 结论 | 依据 / 用例 |
|---|---|---|---|
| 1 | **位图下标基准** | ⚪ **不适用**（已写明） | 本件周次是文本、不是位图，见 §6。本件自己的周次口径由 `fixtures/weeks-text` 钉住，并在 §6 写明。 |
| 2 | **`TaskActivity` 参数位** | ⚪ **不适用** | 本件没有 `TaskActivity`（课程从 DOM 读）。 |
| 3 | **`index = 星期 * unitCount + 节次` / 禁止 `eval`** | ✅ | 前一半不适用（没有线性下标寻址）；**后一半本件正是主角**：上游的 `eval("(" + text + ")")` 已换成 `JSON.parse` + 自写只读扫描器，见 §2。 |
| 4 | **`unitCount` 要真的读** | ⚪ **不适用** | 上游**没有** `unitCount`（本件不按线性下标定位），本适配器也不假定节次数。节次来自表头「第N节」与格子所在行。 |
| 5 | **作息时间从哪来** | ✅ | 上游**没有内置作息表**，从课表表头自己的「第N节 HH:mm-HH:mm」读。本适配器两条来源，优先级：**格子自己带的时间**（「(第5-6节 15:00-16:35)」，表头被打印成连堂或表头那条时间不合法时由它顶上）→ **表头的时间**。所有时间过 `hhmmOf` 的 `HH:mm` 与 `00:00–23:59` 校验，并检查起止先后；越界 / 起止颠倒 / 读不出来的一律丢弃并计数进 `warnings`（越界不是跳过一节，是**整个载荷被拒**）。两条都没有时：表头连节次数都没给出 → `periodTimes` 留空 + warn（宿主的默认作息表顶上来，`fixtures/edge-cases` 钉住）；表头给出了节次数只是没时间 → 按每节 45 分钟补一组占位时间 + warn（否则会有节次却没有任何时间，「第 1 节」从 00:00 起，`0-44` 分钟全落在 `00:00–23:59` 内）。**绝不假装那是教务的真实作息。** |
| 6 | **开学日** | ✅ | 自动取当前学期（`today` 落在 `[startDate, endDate]` 里的那一个；没有就取最近一个已开始的；列表整个没有就取第 0 条），开学日取**起止日期里的 `startDate` 回退到那一周的周一**（手册 §4.3）。**任何一种取自哪里都写进 `warnings`**：教务给的写「取自教务给的学期起止日期（…），第 1 周按 …（周一）计」；推算的写「开学日期教务没有提供，已按最近的周一（…）推算」。上游的 `showSingleSelection` 让用户选学期的路径**已删除**。`fixtures/term-pick` 钉住「起止日期是周三 → 回退到那一周的周一」「列表里挑的是最近一个已开始的学期」两条，`fixtures/edge-cases` 钉住推算分支，`fixtures/basic` 钉住教务给日期分支。 |
| 7 | **周次上限** | ✅ | `MAX_TOTAL_WEEKS = 30`（与批次三同口径）。课表里最大周次超过 30 时把 `totalWeeks` 夹到 30、并把所有块的 `endWeek` / `startWeek` 夹进 30，同时写一条 `warnings`。`fixtures/weeks-text` 里有一条 `1-40周` 的毕业设计，期望值就是截断到 `1-30` + 那条告警。 |
| 8 | **`teacher` / `location` 拿不到就留空** | ✅ | `teacher: course.teacher || null`、`location: course.location || null`。上游写的是 `nameMatch[3].trim()`（空串）与 `room`（空串），本件统一成 `null`（手册 §4.7）。`fixtures/basic` 的「形势与政策」就是教师栏为空的那种（`teacher: null`）。**不写「未知」**。 |
| 9 | **学期名** | ✅ | 优先用教务给的：`schoolYear + '学年第' + name + '学期'`（`fixtures/basic` 的 `2025-2026学年第1学期`）。拿不到时用「河南科技大学 + 按导入日期推的学年学期」+ 一条 `warnings`（`fixtures/edge-cases`）。**没有用适配器名当学期名**。 |
| 10 | **`allowHosts`** | ✅ | 空数组 + 同源相对路径，见 §3。上游无绝对 URL，所以没有从上游抄来的主机名。 |
| 11 | **`warnings` 上限** | ✅ | 所有提示统一走 `warn()`：每条按字符截断到 200 字（截断处补 `…`），总数 ≤ 20，重复文本去重。任何调用点都绕过不了这两个上限。 |
| 12 | **变异测试** | ✅ | 见 §10：12 处故意改坏，各自红了对应的用例；源码用 sha256 前后比对确认未被改动。 |

---

## 9. 移植时删掉 / 改掉的上游行为

| 上游 | 本适配器 | 为什么 |
|---|---|---|
| `eval("(" + text + ")")` | `JSON.parse` + 自写只读容错扫描器 | 手册 §5 第 6 条，见 §2。 |
| `promptUserToStart()`（`showAlert` 开场三句话） | 删除 | 空课的导入流程自己会确认；适配器只负责交数据。 |
| `showSingleSelection` 选学期 | 删除，改成**自动取当前学期** | 手册 §3 第 1 步：能用页面解决的别弹窗。用户此刻就停在课表页上，他看得见自己选的是哪一学期。 |
| `showToast` / `notifyTaskCompletion` | 删除 | 上游的推送式交付；我们的是拉式（返回值 / `__ncDone`）。 |
| `console.log("HAUST调试: …")`（把每格 title 写进控制台） | 删除 | 上游的调试输出；本件一次 console 都没有。 |
| `parseTitle` 固定三段括号正则 | 改成逐括号组向左游标切 | 上游遇到课程名里带括号（「大学物理（一）」）就错位、一格两门课时只解析第一门。 |
| `parsePeriod` 只认区间 | 单节次也认（「3节」= 第 3 节） | 上游返回 `null` 会把整门课丢掉。本件节次优先由行位置给出，格子自己的节次只作兜底。 |
| `day = colIndex`（列号就是星期），`day < 1 \|\| day > 7` 就丢 | 三条路：表头「星期X」→ 单元格 id `TD<行>_<星期>` → 都不行就计数进 `warnings` | 树维课表首列是标签列，列号与星期不等价；上游要求 `day ∈ 1..7` 本身也是靠标签列占掉 0 列。 |
| `parseWeeks` 展开成显式周次数组 | 折成极大段 + 单双类型（手册 §4.1） | 我们的载荷是 `(startWeek, endWeek, weekType)`。 |
| 周次读不出来 → `courses.push` 之前 `return []`（**整格静默丢掉**） | 括号组认出来就是一门课，周次读不出来也产出课程，计数进 `warnings` | 手册 §4「不许静默丢课」。 |
| 教师 / 教室为空时留字符串 | `null` | 手册 §4.7：空着比写「未知」好（「未知」会当成真名显示）。 |
| `Array.from(row.cells)` | 用兄弟节点数出列号 | ES5 硬要求；且 iframe 取出的文档在旧 WebView 上未必给全 `row.cells`。 |
| `key.startsWith("y")` | 手写 `indexOf(...) === 0`（实际实现更宽：任何数组值的键都收） | `String.prototype.startsWith` 在 ES5 里没有。 |
| `td[title]` 找不到就退回 `td[id^='TD']`（两条路互斥） | 三条选择器合并去重，`title` 为空时退回格子文字 | 上游只在**整页**都没找到 `td[title]` 时才换选择器；页面上只要有一格有 title，其它只有文字的格子就全被丢掉。 |
| 按「课名_星期_起始节_周次」判重（不看教师与教室） | 判重键加上节次终点、单双类型与教室 | 上游会把同一门课**不同教师**或**不同教室**的两条安排当成重复，静默去掉一条。 |
| 表头只读「第N节 + 一个时间」，节次时间写死取第一处匹配 | 认「第N-M节 HH:mm-HH:mm」（同意一段时间的两节都收），并支持一个标签吃多行时逐行分派节次 | 上游的 `TIME_RE` 找的是整格里第一处时间，连堂标签会串到别的节次上。 |
| 格子自带的时间（「(第5-6节 15:00-16:35)」）不认，会被当成教师 | 先摘出来当时间用（优先于表头），并给它一个空教师 | 上游的教师取的是明细组左边那个括号组，遇到这种写法教师会变成一串时间文本。 |

---

## 10. 变异测试记录（2026-09-17）

用 Node `vm` 载入 `parse.js`，在内存里对源码做字符串替换（**不落盘**），每份 fixture 各跑一遍：

```
basic  weeks-text  edge-cases  term-pick  semester-fallback
```

### 10.1 单点变异（临时脚本，四份 fixture 一起跑）

| # | 改坏的地方 | 变红的用例 | 红在哪 |
|---|---|---|---|
| M1 | `if (odd) result.odd = true;` → `if (false) …`（丢掉单周标记） | `basic`、`weeks-text` | 大学英语的块从 `3-17 ODD` 变成 `3-17 ALL`（每周都上） |
| M2 | 课程名取成教师 | 五份全红 | 课名全变成教师名（「张伟」「李娜」…），教师字段为空 |
| M3 | 星期回退算成 `id 下标 + 1` | `edge-cases` | 离散数学的 `dayOfWeek` 从 2 变 3 |
| M4 | 不夹 30 周上限 | `weeks-text` | 毕业设计的 `endWeek` 从 30 变 40、`totalWeeks` 从 30 变 40、少一条告警 |
| M5 | 周次 `issues` 不计数 | `edge-cases` | 少一条告警（8 → 7 条） |
| M6 | 单节次不认（只认区间） | 五份全红 | `detailOf` 认不出「3节」「5节」这类括号组，大学英语/线性代数等课整格消失 |
| M7 | 空格不再作为周次分段符 | `weeks-text`、`edge-cases` | 计算机组成原理只剩 `1-3`（`5-9` 整段消失）、大学物理的 `1-16周 双` 读不出双周 |
| M8 | `warnings` 不去重 | 五份全绿 | 本批用例里没有重复告警，所以这条**没有被用例看着** —— 如实记在这里，不假装它被覆盖了 |
| M13 | 格子自带的时间不生效 | `term-pick` | `periodTimes` 3 → 2 条（第 5 节的 `15:00-16:35` 没了） |
| M14 | 不挑当前学期（永远取列表第 0 条） | `term-pick`、`semester-fallback` | 两份都取成了列表第一条（`2024-2025学年…`），开学日跟着错 |
| M15 | 表头脏时间不校验（起止颠倒 / `24:30` 照收） | `term-pick` | `periodTimes` 3 → 4 条，多出一条第 4 节的 `10:50-10:00`（起止颠倒） |
| M16 | 表头缺时间的节次不计数 | `term-pick` | 少一条告警（4 → 3 条），「2 节只写了第N节没有时间」整条消失 |
| M17 | 开学日不回退到周一 | `term-pick` | `firstDay` 从 `2026-09-07` 变 `2026-09-09`（周三） |
| M18 | 星期定位失败时猜成星期一（不跳过） | `edge-cases` | 定位不到星期的那门课被塞进星期一：`courses` 2 → 3 门、少一条告警 |

### 10.2 链式变异（覆盖「核心逻辑被整体推翻」）

| # | 改坏的地方 | 变红的用例 | 红在哪 |
|---|---|---|---|
| M9 | 单双标记 + 星期回退 + 不夹 30 周（三处同时） | `basic`、`weeks-text`、`edge-cases` | 星期整体偏一天、单双消失、周次越界 |
| M10 | 课名与教师读反 + title 优先逻辑改坏 | 五份全红 | 课名/教师互换 |
| M11 | `runsOf` 的单双分支全部 `if (false)` → 所有块产 `ALL` | `basic`、`weeks-text` | 大学英语 `ODD`→`ALL`、线性代数 `EVEN`→`ALL`、大学物理 `EVEN`→`ALL` |
| M12 | 所有失败计数都不进 `warnings`（静默丢课） | `edge-cases` | 8 条告警只剩 4 条（丢掉了「1 个格子格式不符」「1 段周次读不出来」「1 个格子没能定位到星期」「1 个格子没能定位到节次」）**而课程数据一字不变** —— 这正是「静默丢课」最难发现的地方 |

### 10.3 还原确认

```
sha256(parse.js) BEFORE = d92e84495e1075510b14987d24a91eb3899103a835d933dfcc917fccfbc683b0
原始         {"basic":"MATCH","weeks-text":"MATCH","edge-cases":"MATCH","term-pick":"MATCH","semester-fallback":"MATCH"}
M1..M18 见 §10.1 / §10.2（每次都只红上表列出的那几条）
sha256(parse.js) AFTER  = d92e84495e1075510b14987d24a91eb3899103a835d933dfcc917fccfbc683b0
源码未被改动: true
```

十八次替换（M1–M18，含链式的 M9–M12）全部在内存里做，跑完重新读文件，sha256 与跑之前**逐字节一致**。

### 10.4 载荷契约自检（不是变异测试，是每份用例跑完后的校验）

对四份用例的输出逐条按 `JwSchedulePayload.validate` 的口径自查：`warnings` ≤20 条且每条 ≤200 字、
`totalWeeks` ∈ 1..30、`dayOfWeek` ∈ 1..7、`startPeriod ≥ 1` 且 `startPeriod ≤ endPeriod`、
`startWeek ≥ 1` 且 `startWeek ≤ endWeek ≤ totalWeeks`、`weekType` ∈ {ALL, ODD, EVEN}、
`name` 非空、每个 `periodTimes` 的 `start` / `end` 都匹配 `^([01]?[0-9]|2[0-3]):[0-5][0-9]$`
且 `periodIndex ≥ 1`。**五份全部 0 违规。**

### 10.5 extract.js 冒烟（DOM 桩，只证明它能跑起来）

`extract.js` 在 CI 里跑不到（需要浏览器），但可以在 Node 的 `vm` 里塞一个最小 DOM 桩跑一遍，
确认它**能执行完**且交出去的键集与 fixture 对得上（临时脚本，未落仓库）：

```
keys: cells,days,frames,periods,semesterError,semesterGroups,semesterSample,semesters,source,title,today,url
semesters: [{"schoolYear":"2025-2026","name":"1","startDate":"2025-09-01","endDate":"2026-01-18"}]   ← 从「({"…"})」包装里剥出来的
periods[0]: {"index":1,"start":"08:00","end":"08:45","row":1,"col":0}
sample cell: {"row":1,"col":1,"span":1,"id":"TD0_1","className":"","title":"…","text":"…"}
键集与 basic.extracted.json 一致；格子键集也一致
```

**这不等于真机验证**（DOM 桩是我按上游选择器搭的，真实的树维课表页未必长这样），
它只排掉「脚本根本跑不起来 / 键名写错」这一类错。

---

## 11. 已知边界与没做的事

1. **fixture 是合成的**。维护者手上没有可公开的真实 HAUST 课表页，`fixtures/*.extracted.json`
   是按上游代码里的选择器、`;;;` 分隔说明与同平台通行的课表结构**编造**的（学校名是真的、
   课名/教师/课号是编的）。它只保证「同样的输入永远得到同样的输出」，**不保证真实页面上
   解析一定对**。拿到真实 dump（脱敏后）请替换 `fixtures/` 并重跑门。**代价**：真实页面里
   课程名/教师/教室的脏写法（多余空格、全角括号、教师多人用「,」分隔）没有被覆盖到 ——
   遇到时按现在的口径会进 `warnings`（不静默），但可能真的读不出来。
2. **教务主机名未知**（§3.3）。相对路径请求在 VPN 单域下必然通过；若课表页在另一个域上，
   需要人工把该域加进 `manifest.allowHosts`。这是本件唯一需要真机核对的配置项。
3. **`data.semesters` 的分组形状按上游 `key.startsWith("y")` 放宽成「任何数组值的键」**，
   并额外支持一层嵌套。真实响应若还有更深/更怪的形状，学期列表会取不到 → 降级成「按导入日期
   推算学期名与开学日」+ `warnings`（不会丢课表，但开学日不准）。
4. **学期起止日期的字段名**按上游注释里的 `startDate` / `endDate` 读写（另接受 `start` / `end`
   作为别名，`schoolYear` 另有 `schoolYearName` 别名）。真实字段名若不同，表现为「学期名与
   开学日都变成推算值」+ `warnings`。
5. **节次与星期的定位**依赖表头「星期X」或单元格 id 的 `TD<行>_<星期>` 形态。两者都不在时，
   本件**不猜**：课会被跳过并计数进 `warnings`（`fixtures/edge-cases` 钉住这个分支）。
6. **一台课表页上的多个学期**：上游一次只处理用户选的那一个学期，本件同样只产出**一个**学期
   （自动挑当前学期）。载荷支持 `terms[]` 多学期，但本件的取数方式（读当前渲染出来的 DOM）
   天然只对应页面上的那一个学期，所以没有做多学期。
7. **`;;;` 分隔**：上游注释与同族 `HPU` 的 `replace(/;;;/g, " ")` 都指向「一个格子里多门课用
   `;;;` 拼起来」。本件在 `parseTitle` 里先把 `;;;` 换成空格再切括号组 —— 合成用例里**没有**
   真的构造 `;;;` 的格子（真实形态未见过），所以这条路径**没有被用例覆盖**，属于已知不确定。
8. **`MAX_PERIOD = 40`** 是「合法作息不会超过 40 节」的判断，不是教务的约定。超出的一律
   当脏数据丢弃并计数进 `warnings`（宁可报出来，也不让整包因非法数据被拒）。
9. **表头只有节次数、没有时间时补的占位作息**（每节 45 分钟、第 1 节 00:00 起）是**明确标注过的
   假值**：它保证课表每节都有时间可显示、且时刻全部合法（不会让整包被拒），同时 `warnings` 里
   直说「请在节次设置里改成教务的实际作息」。**不假装它是教务的真实作息。**
10. **同一格多门课**的分界靠「周次,节次[,教室]」括号组；同一格里出现两个只有周次的组、且
    第二个组前面没有任何课号/教师括号组时，第二组的名字会取到前一组结束到它自己之间的文字。
    合成用例只覆盖了「一格一门课」与「一格一门课 + 自带时间组」，**一格真的串两门课的写法
    没有被用例覆盖**（真实形态未见过）—— 与 `;;;` 那一条同源，都是已知不确定。

---

## 12. 出处与签名

- 上游：`shiguang_warehouse` / `resources/HAUST/haust.js`（MIT，作者 **Haooz**），
  快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）；
  同目录 `adapters.yaml`：`adapter_name: 河南科技大学树维教务`、`maintainer: Haooz`、
  `import_url: https://vpn.haust.edu.cn`。上游社区公约要求保留贡献者记录 ——
  `manifest.json` 的 `author` 与两份脚本的文件头都写了出处。
- 本适配器的全部逻辑由我逐行读过并重写为 ES5 两段式；`extract.js` 只取数（一条同源请求 + 读 DOM），
  `parse.js` 是纯函数（无网络、无 DOM、无全局读写）。
- 移植者：**0x7E7-2023**　日期：**2026-09-17**
