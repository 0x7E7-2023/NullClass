# 安全审计 —— 合肥师范学院（树维 EAMS 平台）

审计对象：`jw-adapters/hfnu/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `HFNU/hfnu.js`（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，
2026-09-12，MIT，上游作者 `星河欲转`）。

审计方式：逐行读上游脚本 + 逐行读移植件，并跑移植手册 §5 给的静态扫描
（`grep -oE "https?://[A-Za-z0-9.-]+"` 与 `grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*="`）。
扫描结果：两个移植脚本里出现的绝对 URL 只有两处 —— `extract.js` 文件头里写上游出处与上游绝对地址的
`https://github.com` / `https://jw.hfnu.edu.cn`（都是**注释**，不是请求），`parse.js` 里只有
`https://github.com`（文件头）。网络调用只有 `extract.js` 里的一个 `fetch` 包装函数
（`X-Requested-With: XMLHttpRequest` 是请求头，不是 XHR 对象）。`parse.js` 里没有
`window.` / `document.` / `fetch` 的任何**代码**引用（`window.shiguangBridgePromise` 只出现在
文件头的说明文字里，用来交代上游做了什么）。

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

**`allowHosts: []` —— 请求主机只有用户当前所在的那一个教务主机。**

上游 `HFNU/hfnu.js` 三条请求写的都是**绝对 URL**，主机名硬编码成 `https://jw.hfnu.edu.cn`：

| # | 上游原文 |
|---|---|
| 1 | `https://jw.hfnu.edu.cn/eams/courseTableForStd.action?sf_request_type=ajax` |
| 2 | `https://jw.hfnu.edu.cn/eams/dataQuery.action?sf_request_type=ajax` |
| 3 | `https://jw.hfnu.edu.cn/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` |

**移植件的选择与依据**：三条请求的地址全部改由 `window.location` 拼出来
（`originOf()` + `jwBase()`），脚本里**不再出现任何主机名**：

- `originOf()` 取 `window.location.origin`（老 WebView 没有 `origin` 时用 `protocol + '//' + host`）；
- `jwBase()` 取当前路径里 `/eams/` 之前的那一段，拼成 `<origin><前缀>/eams`；
  当前路径里没有 `/eams/` 时回落到 `<origin>/eams`。

于是：正常从 `https://jw.hfnu.edu.cn/eams/loginExt.action` 登录的使用者，请求仍然打在
`jw.hfnu.edu.cn` 上；学校若哪天换成 `http`、加端口、或把 EAMS 挂在门户 / WebVPN 的前缀下，
请求跟着用户实际打开的那个源走。**这正是移植手册 §5 第 3 条要的形态：只放行 `loginUrl`
那一个同源主机，`allowHosts` 留空、不写通配。** 代价与依据都写清了 —— 代价是「用户从非
`jw.hfnu.edu.cn` 的源打开时，脚本会向那个源发同样的三条路径」，但那种情况下页面本身就是
那个源提供的教务系统，脚本没有把任何数据发往用户没打开的域。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action?sf_request_type=ajax` | 读 `ids`（学号栏）与 `tagId`（学期栏元素 id）。**与上游逐字一致** | 报错并提示重新登录（没有这两个参数就查不了课表） |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action?sf_request_type=ajax` | 取学期日历（`tagId=…&dataType=semesterCalendar`，**与上游逐字一致**），里面带当前学期 `semesterId` 与各学期起止日期 | 报错并提示重新登录 |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | 取课表 HTML（`ignoreHead=1&setting.kind=std&semester.id=<id>&ids=<ids>`，**与上游逐字一致**） | 报错并提示重新登录 |

- 没有第四条请求。上游也只有这三条。
- 请求头只带 `Content-Type` / `X-Requested-With` / `Accept`，**不带任何自定义令牌**；
  Cookie 由 WebView 自己按同源规则带上，脚本既不读它、也不把它发到别处。
- `parse.js` 不发任何请求（CI 里用 Rhino 实跑，是纯函数）。

## 2. 读了什么（数据面）

| 数据 | 从哪来 | 是否带出 |
|---|---|---|
| `ids`（学号栏的值）、`tagId`（学期栏元素 id） | 课表页 HTML（`courseTableForStd.action` 的响应文本） | **不带出**：只用它们拼第 3 条请求的请求体，不写进 extract 的输出 |
| 学期列表（`id` / `schoolYear` / `name` / `startDate` / `endDate`）与 `semesterId` | `dataQuery.action` 的响应文本 | 带出（学期名、开学日、总周数要用） |
| 课表 HTML 全文（内嵌 `var unitCount`、`var teachers` / `var actTeachers` 姓名块、`new TaskActivity(...)`、`index = 星期*unitCount+节次`） | `courseTableForStd!courseTable.action` 的响应文本 | 带出（这就是课表本身） |
| 页面正文里有没有出现「锦绣」/「滨湖」 | 当前页面 DOM（`document.body.textContent`） | 只带出**两个布尔值**，正文一个字都不带出 |
| 校区单选按钮的 `value` / 相邻 `label` 文字（截断到 40 字）/ 是否勾选 | 当前页面 DOM（`querySelectorAll('input[type=radio]')`） | 带出（校区判定要用） |
| 课表表格里「第N节(08:00-08:45)」这类时间 | 当前页面 DOM（表格文本，读不到再退回正文） | 带出（校区判定要用） |
| 当前页面 URL（`window.location.href`） | 页面 | 带出（排障用；fixture 里保留，AUDIT §6 说明这是刻意留的） |
| 学生的其它信息 | —— | **不读**：不碰登录表单、不读 `localStorage` / `sessionStorage`、不碰成绩 / 学籍 / 个人信息 / 缴费模块 |

不读的东西（静态扫描无命中）：`password` / `pwd` / 登录表单值、`localStorage` / `sessionStorage`、
`document.cookie`、成绩、学籍、缴费、任何其它模块的接口。课表 HTML 里夹带的学生信息
（有的部署会在页面上放姓名学号）**不进 fixture、不写进 warnings、不拼进载荷** ——
`extract.js` 原样交出的就是那一整段 HTML，`parse.js` 只从中取课程名 / 教师 / 教室 / 周次位图 /
`index` 行，其余字符一律丢弃，也不落进输出。

## 3. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问（静态扫描无命中）。唯一的「凭据」是浏览器自带会话 Cookie，脚本既不读它也不把它发到别处。`ids`（学号栏的值）只是拼请求体的参数、**不写进输出**。上游的两个 `showSingleSelection` 弹窗**全部没有移植**：学期改成取教务自己的当前学期，校区改成按页面线索自动判定，一个字段都不问用户要 |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里，地址全部由 `window.location` 拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域（脚本里出现的绝对 URL 只有文件头注释里的 `https://github.com` 与 `https://jw.hfnu.edu.cn`，都不是请求） |
| 3 | 请求域可控 | **过，且不写通配**。代码里不出现任何主机名，请求跟着当前页面的源走 ⇒ 实际只有一个主机（= `loginUrl` 的 `jw.hfnu.edu.cn`，或用户自己打开的代理前缀）。`allowHosts` 留空（详见 §1 的选择与依据） |
| 4 | 只读课表 | **过**。三条请求全部落在 `/eams/courseTableForStd*` 与 `/eams/dataQuery.action`（课表模块），**不碰成绩、学籍、个人信息、缴费**等任何其它接口。`dataQuery.action` 只取 `semesterCalendar` 一种 `dataType`（学期日历），不取任何其它 `dataType` |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测，也没有 `img` 打点 |
| 6 | 不 eval 远程代码 | **过**。两个脚本都没有 `eval` / `new Function`（静态扫描无命中）。**上游在这里是错的**：它用 `Function("return (" + raw + ")")()` 求值 `dataQuery.action` 的响应 —— 把**网络取回来的字符串**当代码执行，命中本条。移植件换成了 `JSON.parse` + 一次「去掉外层圆括号」的宽松处理（`lenientJson`，见 `extract.js`），解析不出来就交 `null`、走推算并在 warnings 里说明。说明文字保留（本条要求把上游的问题写清楚），代码里没有 |
| 7 | 不写页面 | **过**。`extract.js` 只**读**当前页面已有的文本与控件状态（正文里有没有校区名、校区按钮的 value/label/checked、表格里的节次时间）；`fetch` 回来的 HTML 只做字符串与正则处理，不插入当前页面。既不写 DOM、不改表单、不触发提交或点击，也没有 `showToast` / `notifyTaskCompletion` 这类桥调用。`parse.js` 更是纯函数（没有 `window.` / `document.` 的代码引用） |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有上下文路径 `/eams`、`unitCount` 缺省 14、两套校区作息表、周次上限 30 与节次上限 20 —— 前两个是页面上可见的部署参数，作息表是脚本作者对学校的了解（**已在 warnings 里说明它没跟教务核对过**） |

**结论：可以进内置库。** 全部请求落在本校教务主机上，只读课表（+ 只读的学期日历），
不碰凭据、不外发、不埋点、不写页面、不 eval 远程代码。

## 4. 本批（批次四）12 条检查表逐条

| # | 检查项 | 本件结论 |
|---|---|---|
| 1 | **周次位图的下标基准**（本批最大的坑） | **过，且专门写了一节（见 §5）**。上游 `for (j = 0; j < len; j++) if (bitmap[j] === '1') weeks.push(j)` 会把第 0 位 push 成「第 0 周」；本适配器按本批统一口径实现（`i >= 1`）。用例：`weeks-bitmap` 里「第 0 位为 1」与「第 1 位为 1」两条对照都有。变异 M1 证明这条用例真的在看它 |
| 2 | **`TaskActivity` 的参数位** | **过**。`args[3]`=课程名、`args[5]`=教室、`args[6]`=周次位图**与上游一致**；`args[1]`（教师）两副面孔都处理了：是字面量就直接用（剥引号），是 `xxx.join(",")` 表达式就先剥、再从**这条安排所属的姓名块**里取（上游只认「字面量」一种，`join(...)` 那种会把整个 `join(...)` 表达式当成教师名写进去 —— 本件没照抄）。参数切分用上游的 `powerSplit` 逻辑（顶层逗号切、引号与括号里的逗号不算），ES5 化后原样移植 |
| 3 | **`index = 星期 * unitCount + 节次` 的两种写法** | **过，两种都认**。带变量 / 常量乘子的与已算好的裸数字都解析（上游只认前一种，裸数字会被**静默丢掉**）。补了裸数字分支这件事写在文件头 ⑧ 与本节。全部用正则解析，**没有 `eval` / `new Function`** |
| 4 | **`unitCount` 要真的读** | **过**。从课表 HTML 里 `unitCount = (\d+)` 读；读不到按上游缺省的 14 反推，**同时进 warnings**（`unitcount-missing` 用例钉住：同一条 `index = 1*unitCount+3` 在 14 下是星期二第 4 节、在 11 下会是第 3 节，期望值取前者 —— 变异 M5 把它改成 11 会立刻变红） |
| 5 | **作息时间从哪来** | **过，且是本件最难的一条（见 §6）**。上游是脚本内置的**两套校区作息** + 弹窗让用户选。本件保留两张表，改成自动判定；判不出来**不静默选一套**，用锦绣那张并在 warnings 里说明读了哪些线索、为什么没判出来。所有时间逐条过 `HH:mm` 与 `00:00-23:59` 校验（`timeOf` 只接受 `[01]?\d|2[0-3]` 与 `[0-5]\d`），非法条目丢弃并计数进 warnings —— 越界会让整个载荷被拒，不是跳过一节 |
| 6 | **开学日** | **过**。`semesterCalendar` 给了学期起止日期：开学日取**学期起始日所在周的周一**（手册 §4.3，缺省 firstDayOfWeek=1），总周数取起止跨的自然周数；拿不到就回退（开学日 → 今天的周一，总周数 → 20），**两条回退都进 warnings**。`firstday-not-monday` 用例钉住「教务给的是周三 2026-09-09 → firstDay 必须是 2026-09-07」（变异 M8 变红）。上游**没有**开学日概念，这一步是本件新加的 |
| 7 | **周次上限** | **过**。位图里 `i > 30` 的位只计数、不产出周次，并写一条 warnings（`weeks-bitmap` 里第 31-35 位 → 「有 5 个周次超出 1-30 周的上限，已丢弃」）。总周数也 clamp 到 30，且课表里更晚的周次会把总周数抬上去（抬了单独说明）。变异 M7（`MAX_WEEK` 改成 60）会让载荷越界、用例变红 |
| 8 | **`teacher` / `location` 拿不到就留空** | **过**。上游 `let teacherName = "未知教师"` 与 `(args[5] \|\| "未知地点")` 两处都改成留空（`null`）。变异 M4（改回一律写「未知教师」）会让 `basic` / `edge-cases` / `weeks-bitmap` 三条用例变红 |
| 9 | **学期名** | **过**。教务的 `schoolYear` + `name`（「2026-2027」+「1」→「2026-2027学年第一学期」）；拿不到才用「合肥师范学院 + 学年学期」。**没有**拿适配器名当学期名 |
| 10 | **`allowHosts`** | **过**。上游有绝对 URL，但本件把它们全换成跟着当前页面源走的相对路径 ⇒ `allowHosts: []`，**不写通配**。选择与依据见 §1 |
| 11 | **`warnings` 上限** | **过**。代码里 `MAX_WARNINGS = 20` / `MAX_WARNING_CHARS = 200`，超长截断、超条数时最后一条如实说明「另有 N 条因为超出上限没有显示」。9 份期望载荷里最长的一条 175 字、最多 9 条（上限分别是 200 字 / 20 条，见 §9 自检输出） |
| 12 | **变异测试** | **过，8 处变异全部有记录**（见 §7） |

## 5. 位图 0 位口径（本件必须专门讲的一节）

**上游的写法**（`HFNU/hfnu.js` 第 155-158 行，逐字）：

```js
const weeks = [];
for (let j = 0; j < weeksBitmap.length; j++) {
    if (weeksBitmap[j] === '1') weeks.push(j);
}
```

它在第 0 位为 `1` 时会 `push(0)`，也就是产出**「第 0 周」**这个不存在的周次
（载荷校验要求 `startWeek >= 1`，会整包被拒）。

**本适配器的写法**：`bitmap[i] === '1'` 且 `i >= 1` 才是第 `i` 周；`bitmap[0] === '1'`
**不产出周次**，改为写一条 warnings：「有 N 条课程的周次位图第 0 位是 1。树维 EAMS 的位图
第 0 位是占位符、不是第 0 周（本适配器按同族统一口径：下标 i 就是第 i 周），已忽略这一位，
请在导入预览里核对周次」。

**依据**：本批（批次四）对 12 件树维 EAMS 脚本的实测事实表给出的**统一口径** ——
「位图下标 `i` 就是第 `i` 周，下标 0 是占位符」。这条口径由同族五件在代码里共同声明：
`uestc`（`weeks.filter(w => w > 0)`）、`hpu`（`if (text[i] === "1" && i >= 1)`）、
`hunnu`（`for (i = 1; …)`）、`zua`（`for (week = 1; …)`）、`zzvcae`（同 `zua`）。
上游 `hfnu` 没跳 0 位，是它与同族五件不一致，而不是本件与上游不一致 ——
批次文档的「本批最大坑」一节写明 `hfnu` / `xatu` / `tjau` 三件的 0 位是真的会被 push 进周次数组的，
并规定统一按 `i >= 1` 实现。**真机核对时若发现整体差一周，改的就是这一处**
（`weeksOfBitmap` 里 `for (i = 1; …)` 的起始值），改完应同时删掉那条 warnings。

**用例**：`fixtures/weeks-bitmap.*` 里两条对照 ——
- 「数据结构」的位图第 0 位与第 1..16 位都是 `1` → 期望只有第 1-16 周（**没有第 0 周**），
  并带那条 warnings；
- 「线性代数」的位图从第 1 位起为 `1`（1,3,5,…,15）→ 期望第 1-15 周的单周（`weekType: "ODD"`）。

变异 M1（把 `for (i = 1; …)` 改回 `for (i = 0; …)`，即上游原样写法）会让 `weeks-bitmap` 变红。

## 6. 校区作息是怎么判断的、判不出来怎么办（批次检查表第 5 条的展开）

上游 `applyTimeSlots()` 里写死了两张表（锦绣校区 / 滨湖校区），然后用
`window.shiguangBridgePromise.showSingleSelection("选择校区", ["锦绣校区","滨湖校区"], -1)`
让用户选，选不到（返回 `null`）就**整趟导入中止**。本件不弹窗，改成按以下顺序取第一条能用的线索
（每一步读什么，都写在 `extract.js` / `parse.js` 的 `campusPlan` 里，与代码逐条对应）：

| 顺序 | 线索 | 读的是哪里 |
|---|---|---|
| a | 页面上**已勾选**的校区单选按钮（`value` 或相邻 `label` 文字里含「锦绣」/「滨湖」） | `input[type=radio]` 的 `value` / `label[for]` 文字 / `checked` |
| b | 当前页面正文里**只出现一个**的校区名；没有再看课表页 HTML 里只出现一个的校区名 | `document.body.textContent` / `courseTableForStd.action` 的响应文本（只交布尔值） |
| c | 课表表头「第N节(08:20-09:05)」的时间与哪一套表逐字对得上（要求命中 ≥2 节，且明显多于另一套；表头只有 1 节时命中 1 节即可） | 表格文本里的 `第N节 … HH:mm-HH:mm` |
| d | 课程教室 / 地点文本里的校区名（全部指向同一个时才算） | 解析出来的地点串 |

**判不出来怎么办：不静默选一套，也不中止。** 用锦绣校区那张（上游的 `timeSlot1`，
它在脚本里排在第一个、也是主校区名），并写一条 warnings 把**读了哪些线索、为什么没判出来**
逐条说清楚，例如 `campus-unknown` 用例里的那条：

> 没能判断出你在哪个校区（页面上没有已勾选的校区按钮；页面与课表页 HTML 里没有只出现一个的校区名；
> 课表表头读了 2 个节次时间，但与两套校区作息都对不上（锦绣校区 对上 0 节、滨湖校区 对上 0 节）；
> 课程地点里也没有校区名），作息时间暂用锦绣校区的 11 节作息表（第 1 节 08:00-08:45），
> 请在学期管理里核对或改成实际校区的作息

**依据与代价**：上游靠用户选，本件靠线索，线索可能判错，所以判出来的时候**也在 warnings 里
说明依据**（「依据：课表表头的节次时间（共 3 节，与滨湖校区作息对上 3 节）」/「依据：页面上勾选的
校区按钮」/「依据：当前页面正文里只出现了这一个校区名」）。用户看到依据就能判断对不对 ——
这比一句「已用滨湖校区」有用得多。两张表本身**都**是上游脚本内置的、没有向教务核对过，
这一句也留在 warnings 里。

**学期也是自动挑的**（同一类问题，放在这一节一起说）：上游用 `showSingleSelection` 让用户选学期，
本件按「教务自己标的 `semesterId` → 按今天的日期推 → 列表里最后一个」挑（见 `extract.js` 的
`pickSemester`），挑法由 `pickedBy` 字段带出来：`semesterId` 是教务自己的判断、不额外提醒；
`today` / `last` 两种**各写一条 warnings 说明是怎么挑的**（这两种情况可能挑错学期）。
所有 fixture 都用 `semesterId`（教务标了当前学期），`today` / `last` 两条分支只做了人工核对、
没有 fixture 覆盖 —— 这是已知的覆盖缺口（见 §8 第 12 条）。

**用例**：`campus-times`（表头时间 → 滨湖）、`campus-jinxiu`（勾选的按钮 → 锦绣）、
`campus-unknown`（判不出来 → 锦绣 + 说明）。变异 M2（两套表对调）会让 7 条用例同时变红，
变异 M3（匹配关系反过来）会让 `campus-times` 变红。

## 7. 变异测试记录（改坏哪一处 → 哪条用例变红）

改的都是**内存里的副本**（`String.replace` 到变量上再喂给 `vm`），工作区文件一个字节都没动。
基线（未变异）：9 条用例全 MATCH。

| # | 改坏哪一处 | 变红的用例 |
|---|---|---|
| M1 | 周次位图基准：`for (i = 1; …)` 改回上游的 `for (i = 0; …)` | `weeks-bitmap`(DIFF) |
| M2 | 校区作息：锦绣那张表的第 1 节时间换成滨湖的（两套对调） | `basic`, `campus-jinxiu`, `campus-unknown`, `edge-cases`, `firstday-not-monday`, `names-args`, `unitcount-missing`（7 条 DIFF） |
| M3 | 校区判定：表头时间与作息表的匹配关系反过来（判给对不上的那一个） | `campus-times`(DIFF) |
| M4 | 教师：改回上游的一律写「未知教师」 | `basic`, `edge-cases`, `weeks-bitmap`(DIFF) |
| M5 | `unitCount` 缺省：14 改成 11 | `unitcount-missing`(DIFF) |
| M6 | 裸数字 `index` 分支去掉（只认带 `unitCount` 的写法，回到上游行为） | `edge-cases`, `weeks-bitmap`(DIFF) |
| M7 | 周次上限：`MAX_WEEK` 30 改成 60 | `weeks-bitmap`(DIFF) |
| M8 | 开学日：不回退到周一，直接用教务给的学期起始日 | `firstday-not-monday`(DIFF) |

8 处全部有对应用例变红，没有「改了也没人管」的逻辑。M8 是补的用例：
第一轮跑变异时 M8 **没有让任何用例变红**（当时没有「学期起始日不是周一」的用例），
所以补了 `firstday-not-monday` 这一对，重跑才红 —— 这条记录留在这里，因为它正是
「用例看着你以为它在看的那条路径」这件事的证据。

## 8. 已知边界与没做的事（诚实写）

1. **fixture 全是合成的**。我们没有任何一个上游学校的账号，所以 9 对 fixture 都是按上游
   `HFNU/hfnu.js` **实际读取的字段形状**编造的，不是真实抓取（文件头都注明了）。
   代价：只保证「同样的输入永远得到同样的输出」，**不保证在真实页面上解析是对的**。
   谁哪天拿到真实 dump，替换 fixture 并重跑门是最高优先级的贡献。
2. **`label[for]` 与 `document.body.textContent` 的读法在真实页面上没验过**。校区判定的
   a / b 两条线索依赖页面上真有这些元素 —— 真实部署里校区按钮可能根本没有，那时会落到
   c / d 或走「判不出来 + warnings」，**不会静默选错**。
3. **表头时间线索依赖表头真写了时间**。树维 EAMS 的表格骨架是否一定在「第N节」旁写
   `(08:20-09:05)`，我们没见过真实页面；上游 `hfnu` 没读它（同族的 `zua` / `zzvcae` 才读），
   所以这条线索是**借同族做法补的推断**，只在 c 这一档使用，判不出来时不影响导入。
4. **教师姓名块的作用范围是「到下一个姓名块」**。一个姓名块后面跟多条 `TaskActivity` 时
   （`edge-cases` 用例），这些安排共用同一组教师姓名 —— 上游也是这么绑的，但真实页面上
   姓名块与活动的对应关系我们没验过，所以这种情况**写进 warnings**。
5. **`unitCount` 的缺省 14 是上游写的，不是实测的**。合肥师范学院每天几节我们不知道；
   读到页面上的 `unitCount` 就不会用这个缺省，用到时一定进 warnings。
6. **两套校区作息表都是上游脚本内置的**（作者对学校的了解，不是从教务读的）。
   判出来也用、判不出来也用，两种情况都在 warnings 里说明「这张表没跟教务核对过」。
7. **总周数在教务不给学期日历时回落到 20**（上游同族常见的缺省），并进 warnings。
8. **只导入教务当前选中的学期**。要别的学期，用户在教务页面里切一下再点「提取课表」
   —— 这条也写进 warnings（比弹窗选学期更符合手册 §3 第 1 步）。
9. **`extract.js` 不在 CI 里跑**。它需要浏览器与已登录的会话，CI 只跑 `parse.js`。
   `extract.js` 的正确性只能靠真机抽验和用户反馈 —— 它写得尽量薄：三条请求 + 读几个只读线索，
   没有任何转换逻辑。
10. **没有真机验证**（无账号，代价见 `docs/jw-adapter-testing.md` §3）。
11. **fixture 里保留了 `page.url`**（`https://jw.hfnu.edu.cn/eams/courseTableForStd.action`）。
    这是适配器自己的 `loginUrl` 主机、不是个人信息，留着是为了让「用户从哪个源打开」这件事
    在回归里可见；如果维护者认为不该留，删掉它不影响任何解析（`parse.js` 不读这个字段）。
12. **`pickedBy` 的 `today` / `last` 两条分支没有 fixture**。它们在 `extract.js` 里（CI 跑不到），
    只做了人工核对：两条都会写 warnings 说明「学期是这么挑的」，`last` 那条还会带上列表长度。
    要覆盖它们得把学期挑选逻辑挪进 `parse.js` —— 那会让 `parse.js` 依赖当天的日期，
    用例会随日期失效，所以**故意没这么做**。
13. **本件的 CI 自验是 Node 手搓的**，不是 `JwLibraryHarnessTest` 本身：本批 12 个 agent 并发，
    约定谁都不跑 `./gradlew`。比对的语义与那道门一致（Rhino 换成 `vm`、逐字段比对键顺序无关
    数组顺序有关、期望载荷另过一遍规范校验），但**最终仍需主 agent 跑一次真的门**
    （含 `index.json` 条目、ES5 断言与打包一致性）。

## 9. 自验（Node，交活前跑过）

`parse.js` 在 `vm` 里跑 9 份 `fixtures/*.extracted.json`，与 `*.expected.json` 逐字段比对
（键顺序无关、数组顺序有关）：

```
[basic] MATCH
[campus-jinxiu] MATCH
[campus-times] MATCH
[campus-unknown] MATCH
[edge-cases] MATCH
[firstday-not-monday] MATCH
[names-args] MATCH
[unitcount-missing] MATCH
[weeks-bitmap] MATCH
--- pass=9 fail=0 missing-expected=0
```

载荷自检（9 份期望载荷逐条过规范 §4 的校验规则）：

```
basic.expected.json                warnings=4 maxLen=81  periods=11 courses=4 firstDay=2026-09-07 totalWeeks=20
campus-jinxiu.expected.json        warnings=4 maxLen=76  periods=11 courses=1 firstDay=2026-09-07 totalWeeks=20
campus-times.expected.json         warnings=4 maxLen=94  periods=11 courses=1 firstDay=2026-09-07 totalWeeks=20
campus-unknown.expected.json       warnings=4 maxLen=175 periods=11 courses=1 firstDay=2026-09-07 totalWeeks=20
edge-cases.expected.json           warnings=8 maxLen=81  periods=11 courses=5 firstDay=2026-09-07 totalWeeks=20
firstday-not-monday.expected.json  warnings=4 maxLen=81  periods=11 courses=1 firstDay=2026-09-07 totalWeeks=20
names-args.expected.json           warnings=5 maxLen=81  periods=11 courses=2 firstDay=2026-09-07 totalWeeks=20
unitcount-missing.expected.json    warnings=5 maxLen=81  periods=11 courses=1 firstDay=2026-09-07 totalWeeks=20
weeks-bitmap.expected.json         warnings=9 maxLen=94  periods=11 courses=4 firstDay=2026-09-14 totalWeeks=20
MAX warnings=9（上限 20）  MAX len=175（上限 200）
```

`totalWeeks ∈ 1..30`、`firstDay` 是 `yyyy-MM-dd`、每个 `periodTimes` 的 `start`/`end` 都匹配
`^([01]?\d|2[0-3]):[0-5]\d$` 且 `start < end`、`dayOfWeek ∈ 1..7`、`endWeek ≤ totalWeeks`、
`weekType ∈ {ALL,ODD,EVEN}`、课名非空 —— 全部通过。

NUL / 控制字节自检（工具入参里的转义序列会落成真控制字符，本批踩过三次）：

```
$ node -e "var b=require('fs').readFileSync('jw-adapters/hfnu/parse.js');console.log('NUL:',b.filter(function(x){return x===0}).length)"
NUL: 0
extract.js  NUL bytes: 0   control bytes: {}   lines: 330
parse.js    NUL bytes: 0   control bytes: {}   lines: 1116
```

（`control bytes: {}` 表示整个文件里没有任何 < 0x20 的字节（除换行 0x0A 外）。复合键分隔符
用 `String.fromCharCode(0)` 取，源码里既不出现控制字符、也不出现转义序列。）

ES5 检查（CI 扫的是**整个文件字符串**，注释里出现也算违规，所以注释也一起扫）：

```
extract.js  ES5 bad lines: (none)   # 无 =>、无反引号、无 let/const
parse.js    ES5 bad lines: (none)
```

`eval` / `new Function` 静态扫描：两个文件都无命中（上游那一处已在 §3 第 6 条写明，
并且**只在说明文字里提到**，代码里没有）。

## 10. 请求了哪些域（`allowHosts` 的依据）

`allowHosts: []`。唯一会发出请求的脚本是 `extract.js`，它的三条请求都拼自
`window.location.origin`，代码里**不出现任何主机名**：

- 从 `https://jw.hfnu.edu.cn/eams/…` 打开 → 请求 `https://jw.hfnu.edu.cn/eams/…`（= `loginUrl` 同源）；
- 从 `http://` / 带端口的地址打开 → 跟着那个地址；
- 从门户或 WebVPN 的前缀下打开（如 `/proxy/eams/…`）→ `jwBase()` 取当前路径里 `/eams/` 之前的
  那一段，请求跟着那个前缀走。

三条路径分别是 `/eams/courseTableForStd.action`、`/eams/dataQuery.action`、
`/eams/courseTableForStd!courseTable.action`（与上游逐字一致）。
`parse.js` 不发请求。所以白名单留空即可覆盖全部实际请求，**没有任何通配**。

## 11. 移植者签名与日期

- 上游：`shiguang_warehouse` 的 `HFNU/hfnu.js`，作者 `星河欲转`（MIT），
  快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`（2026-09-12）。
- 移植：`0x7E-2023`，日期 **2026-09-17**，批次四（树维 EAMS 整族，第 8 件）。
- 本审计的第 1、2、3、4、6、7、9、10 节的每一条结论都与 `extract.js` / `parse.js` 的实际代码
  逐条核对过（含静态扫描输出）；§7 的变异测试与 §9 的自验在交活前实跑过。
