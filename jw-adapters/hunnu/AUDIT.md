# 安全审计 —— 湖南师范大学教务适配器（`hunnu`）

审计对象：`jw-adapters/hunnu/extract.js` + `parse.js`
（移植自拾光社区 `HUNNU/hunnu.js`，上游快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`
（2026-09-12），上游维护者 EarOfWheat，MIT）

平台：**树维 EAMS**（路径 `/eams/`，上海树维信息科技有限公司 SupWisdom，新开普子公司）。
**不是强智**——上游脚本自称树维，`loginUrl` 也是 `/eams/courseTableForStd.action`。

审计人：0x7E-2023（移植者）　日期：2026-09-17

## 结论

**通过。** 本适配器**不发起任何网络请求**，只读用户当前已打开页面（含「我的课表」iframe）的
HTML 文本，不碰凭据，不读写存储，不埋点，唯一一次「写页面」是上游就有的、把课表 iframe
调出来的链接点击（手册 §5 第 7 条允许的例外，见下面第二节）。

---

## 一、零请求（`allowHosts: []` 的依据）

两段脚本里没有任何请求 API。用移植手册 §5 给的静态扫法：

```bash
grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=" jw-adapters/hunnu/*.js
```

输出（**只有一处命中，且在注释里**——文件头那句「本文件不发起任何网络请求（没有 fetch /
XMLHttpRequest / sendBeacon / WebSocket）」）：

```
jw-adapters/hunnu/extract.js:9:    // 本文件不发起任何网络请求（没有 fetch / XMLHttpRequest / sendBeacon / WebSocket），
```

```bash
grep -oE "https?://[A-Za-z0-9.:-]+" jw-adapters/hunnu/*.js | sort -u
```

输出（两处，**都只是文件头的上游仓库出处链接**，没有任何一处被请求）：

```
jw-adapters/hunnu/extract.js:https://github.com
jw-adapters/hunnu/parse.js:https://github.com
```

其余相关 API 也扫过：

```bash
grep -nE "click\(|\.submit\(|innerHTML\s*=|appendChild|createElement|localStorage|cookie|password|token" jw-adapters/hunnu/*.js
```

命中三处，逐条说明：

- `extract.js:51-52`：形参名叫 `token` 的 `containsToken(value, token)` —— 与令牌无关；
- `extract.js:154`：`link.click()` —— 见下一节；
- 没有 `.submit(` / `innerHTML =` / `appendChild` / `createElement` / `localStorage` / `cookie` /
  `password` 的任何命中；`parse.js` 一处都没有（它连 DOM 都不碰）。

**所以 `manifest.json` 的 `allowHosts` 是空数组 `[]`：连同源请求都不需要。**
课表页本身由 WebView 按 `loginUrl` 打开，那是用户自己手工登录的页面。

另外自查过：`parse.js` 里没有 `eval` / `new Function` / 动态拼字符串求值
（`grep -c "eval(" jw-adapters/hunnu/*.js` 两个文件都是 `0`）。上游同族有脚本把页面里的表达式
当代码跑（`DLMU`、`HAUST`），本件**不照抄**那条路：`index = 星期 * unitCount + 节次` 由
`positionsFromTail` 自己按字符解析，`index = 62` 这种已算好的数字用除法反推。

## 二、`link.click()` 属于手册 §5 第 7 条的哪一个例外

手册 §5 第 7 条要求「不写页面、不改表单、不触发提交」，括号里的例外是
**「上游切到课表页这种点击」**。本件这一处点击正是这一类，逐项写清：

| 项 | 内容 |
|---|---|
| 点的是什么 | `document.querySelector('a[href*="courseTableForStd"][target*="eams-iframe"]')` 选中的**一个 `<a>` 元素**；选择器要求 href 里带 `courseTableForStd`、target 里带 `eams-iframe`，也就是教务首页左侧那个「我的课表」菜单项本身 |
| 什么时候点 | 只在**前两条策略都落空**时：① 当前 URL 里已经有 `courseTableForStd`（已经在课表页）→ 不点；② 页面上已经有 `iframe.eams-iframe` 且 `src` / `data-src` 指向课表页 → 不点。只有两者都不成立才点，点了之后轮询（最多 60 次 × 200ms）等 iframe 出现 |
| 为什么必须点 | 课表页是教务页面里**异步拉出来的 iframe**，不点这一下，页面上根本不存在课表 HTML，适配器就没有任何可读的数据。这是「切到课表页」本身，和手册举的例外完全同形 |
| 点了之后做什么 | 只读 `iframe` 的 `srcdoc` / `contentDocument.documentElement.outerHTML`（字符串）。不往页面写 DOM、不改表单、不发请求 |
| 有没有可能点到别的东西 | 没有。`link.click()` 只在这一处出现（上面的 grep 已证）；脚本不点击任何其它元素，也不 `.submit()` 任何表单 |
| 点了会不会产生副作用 | 会触发教务自己的前端菜单逻辑（显示课表 iframe），这正是用户手动点「我的课表」会做的事；不产生数据写入。`extract.js` 把「是否点过」作为 `clicked` 字段原样交给 parse，回归用例里 `clicked: false` |

**不许点别的任何东西**这条守住了：全文件只有一次 `.click()`。

## 三、开学日只能推算这件事

我们的载荷里 `firstDay` 是必填（它决定「现在第几周」，今日页 / 提醒 / 小组件 / 周视图列全挂在
上面），但**本适配器拿不到任何开学日期**，原因很直接：

- 上游 `hunnu.js` 就是**零请求**的（`fetch` / XHR 一个都没有，已核），它只读当前页面的 HTML；
- 那唯一能给出学期起止日期的接口是 `POST /eams/dataQuery.action`（`dataType=semesterCalendar`），
  同族另外 11 件走的就是它；**本件按上游的做法不请求它**，所以拿不到 `semesterCalendar`；
- 页面 HTML 里也没有可读的校历（课表页只有课程块与 `unitCount`，没有学期起止日期）。

**推算规则**（两档，`parse.js` 里是同一个 `firstDay` 变量）：

1. **页面上的「第N周」选择器有值**（`currentWeek`）：`firstDay = 今天所在那一周的周一 − (N−1) × 7 天`。
   比只看「最近的周一」准一档，因为它是教务自己页面上显示的当前教学周。
2. **没有**：`firstDay = 今天所在那一周的周一`（「最近的周一」）。

两档都**必须**在 `warnings` 里如实说（规范 §4 与批次检查表第 6 条），文案分别是：

```
开学日期教务没有提供，已按页面上显示的「第 3 周」反推为 2026-08-31，请在学期管理里核对
开学日期教务没有提供（本适配器不请求校历接口），已按最近的周一（2026-09-14）推算，请在学期管理里核对
```

**为什么非要说**：推算值和真值在库里长得一模一样。不说，用户就没有任何机会发现整学期偏移
一两天到两周；说了，他能在导入预览里发现并去学期管理改。

**真机核对方法**：拿一条已知周次的课（例如「第 3 周的周一有课」），看导入后落在哪一天。

**如果以后要做得更准**：加一次 `POST /eams/dataQuery.action`（`dataType=semesterCalendar`）就能
拿到学期起止日期与学期名，但那就**不再是零请求**了 —— 需要把主机写进 `allowHosts`、重新审计。
本次移植**故意不做**：上游本来就是零请求件，改动取数范围属于另一件事。

## 四、逐条对照手册 §5 八条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | ✅ 没有 `password` / `pwd` / 登录表单值 / `localStorage` / `cookie` 的读取；没有注入脚本、没有监听输入。`containsToken(value, token)` 的 `token` 是形参名，与令牌无关。 |
| 2 | 不外发 | ✅ 除本校页面外没有任何请求目标；没有 `fetch` / `XHR` / `sendBeacon` / `WebSocket` / `new Image().src`（grep 见第一节）。 |
| 3 | 请求域可控 | ✅ 不发起请求，`allowHosts: []`（没有通配，更没有 `*.edu.cn`）。 |
| 4 | 只读课表 | ✅ 只读课表页 / 外层页面的 HTML 文本、页面 `<select>` 的选项文字、`document.title`、`location.href`、取数当天日期。不碰成绩、学籍、缴费、个人信息页。 |
| 5 | 不埋点 | ✅ 没有统计 / 上报 / 遥测；连 `console.log` 都没有。 |
| 6 | 不 eval 远程代码 | ✅ 没有 `eval` / `new Function`；`setTimeout` 只接收函数；`index` 表达式按字符解析，不求值。 |
| 7 | 不写页面 | ⚠️ **一处例外**：`link.click()`（把课表 iframe 调出来），属手册 §5 第 7 条「上游切到课表页这种点击」，逐项说明见第二节。除此之外只读 DOM（`querySelectorAll` / `getAttribute` / `textContent` / `outerHTML` / `contentDocument` / `options`）。 |
| 8 | 不依赖用户输入之外的秘密 | ✅ 没有硬编码密钥、令牌或他人学号；脚本里没有任何真实账号数据。 |

## 五、逐条对照本批 12 条检查表

| # | 检查项 | 本件做法 |
|---|---|---|
| 1 | 周次位图下标基准（统一口径 `week = index`，`index 0` 不产出周次并进 warnings） | ✅ `weeksFromBitmap` 从 `i = 1` 起（与上游一致），`bitmap[0] === '1'` 时**显式写一条 warnings**（上游是静默吞掉 0 位）；`uniqueSorted` 里还有 `w >= 1` 的第二道闸。用例：`weeks-bitmap`（第 0 位为 1 与只有第 1 位为 1 各一条）+ `basic`（两条都含第 0 位为 1）。 |
| 2 | `TaskActivity` 参数位 `args[3]` 课名 / `args[5]` 教室 / `args[6]` 位图；`args[1]`（教师）可能是表达式 | ✅ 参数位照写（`ARGS_NAME=3` / `ARGS_LOCATION=5` / `ARGS_WEEKS=6`）；教师**按上游 HUNNU 的读法**取同一块 `actTeachers` 里的名字，不取 `args[1]`（本件 `args[1]` 只是同一名字的字面量，见「已知边界」）。`stringOfArg` 对表达式参数会拼出里面的字面量、读不出就留空。 |
| 3 | `index` 两种写法（带变量 / 已算好）；禁止 `eval` / `new Function` | ✅ `positionsFromTail` 两种都认：`index = 5*unitCount+2`、`index = 5*14+2`（裸数字乘数）、`index = 62`（按 `unitCount` 反推）。解析是手写字符扫描，**没有任何动态求值**。已知边界：`unitCount` 没读到时裸数字 `index` 会按内置 13 反推错（已 warn）。 |
| 4 | `unitCount` 要真的读，读不到必须 warn | ✅ 先读**课表 HTML 里的** `unitCount`（最贴近数据），其次页面 `extract.js` 读到的 `data.unitCount`；都读不到才用上游常量 13 并写 warnings；两者不一致时写 warnings（`computed-index` 用例专测这条）。 |
| 5 | 作息时间来源要写清，且必须过 `HH:mm` 与 `00:00–23:59` | ✅ 来源：**上游 `hunnu.js` 内置的 13 节表**（文件头 `TIME_SLOTS`，第一行 08:00–08:45），`AUDIT.md` 见下节「作息时间来源」。13 行全部是 `HH:mm` 且落在 `00:00–23:59`、`start < end`（逐个核过），`periodIndex` 1..13 连续。 |
| 6 | 开学日拿不到就推算并 warn | ✅ 见第三节；两档推算都写 warnings。 |
| 7 | 周次上限 30（越界 clamp 并 warn） | ✅ `totalWeeks > 30 → 30`，越界的 `endWeek` / `startWeek` 截到 30 并写 warnings（`skips` 用例：位图只有第 33 位是 1 → 变成第 30 周 + 一条截断说明）。 |
| 8 | `teacher` / `location` 拿不到就留空 | ✅ 教师读不到 → `null`；教室为空串 → `null`。没有「未知教师」「未知地点」这类串（`computed-index` 用例里有「教师数组缺 name → teacher: null」）。 |
| 9 | 学期名用教务给的，否则「学校名 + 学年学期」 | ✅ 优先页面上的「20xx-20xx学年 第X学期」（`title` + 学期名候选），拿不到用「湖南师范大学 2026-2027学年第一学期」并写 warnings。不用适配器名当学期名。 |
| 10 | `allowHosts` 精确、不许通配 | ✅ 本件零请求 → `allowHosts: []`。 |
| 11 | `warnings` ≤20 条、每条 ≤200 字 | ✅ `warn()` 里 `MAX_WARNINGS = 20` 硬截断 + 去重；各条文案实测最长 66 字（见下）。 |
| 12 | 变异测试 | ✅ 见第六节。 |

## 六、作息时间来源（检查表第 5 条要求写清）

`parse.js` 里的 `PERIOD_TIMES` 是从**上游 `HUNNU/hunnu.js` 文件头的 `TIME_SLOTS` 常量**
逐行抄来的（该上游脚本在导入时把它当「预设作息」写给应用）：

| 节 | 时间 | 节 | 时间 | 节 | 时间 |
|---|---|---|---|---|---|
| 1 | 08:00–08:45 | 6 | 13:30–14:15 | 11 | 19:00–19:45 |
| 2 | 08:55–09:40 | 7 | 14:30–15:15 | 12 | 19:55–20:40 |
| 3 | 10:00–10:45 | 8 | 15:25–16:10 | 13 | 20:50–21:35 |
| 4 | 10:55–11:40 | 9 | 16:30–17:15 | | |
| 5 | 12:45–13:30 | 10 | 17:25–18:10 | | |

**来源与依据**：上游适配器作者为该校园区写的常量；我们**没有**第二个独立来源核对，
也没有实测页面（课表 HTML 里不写时间）。所以：① 载荷里只发课表**实际用到**的那几节
（不发满 13 行），② 每次都写一条 warnings 提醒「如与教务处公布的作息不一致请在节次设置里调整」。
**真机核对**：对照教务处公布的作息表看第 5 节 12:45 起（午休偏早）与第 6 节 13:30 起是否属实，
不符就改 `parse.js` 的 `PERIOD_TIMES` 这一处。

`unitCount` 同理：上游**只硬编码常量 13**（已核，没有从页面读的逻辑）。本件改成优先读页面值，
真机核对时若发现课表整体错行，先看 warnings 里有没有「节次数没能从页面里读到」——
那说明这次用的是内置 13。

## 七、变异测试记录（改坏哪一处 → 哪条用例变红）

方法：把 `parse.js` 读进内存改坏（**不动工作区文件**），在 Node 的 `vm` 里对四对 fixture 重跑，
与 `*.expected.json` 逐字段比对。四对用例：`basic`（基本 + 重复渲染）、`weeks-bitmap`（位图基准）、
`computed-index`（裸数字 index / 转义引号 / 缺 name / 无 marshalTable）、`skips`（参数不足 / 空位图 /
节次越界 / 30 周截断）。基线（未改坏）四对全 PASS。

| 改坏的地方 | basic | weeks-bitmap | computed-index | skips |
|---|---|---|---|---|
| 位图基准 A：`weeks.push(i)` → `weeks.push(i + 1)`（整体偏一周） | 🔴 | 🔴 | 🔴 | PASS |
| 位图基准 B：循环从 `i = 0` 起 **且**去掉 `w >= 1` 闸（真的产出「第 0 周」） | 🔴 | 🔴 | 🔴 | PASS |
| `TaskActivity` 参数位：`ARGS_NAME` 3 → 5（课名与教室对调） | 🔴 | 🔴 | 🔴 | 🔴 |
| `index` 寻址：`day = left.value + 1` → `day = left.value` | 🔴 | 🔴 | 🔴 | 🔴 |
| 周次段切法：隔周段一律 `weekType = 'ALL'` | PASS | 🔴 | PASS | PASS |
| 教师兜底：拿不到教师时写死「未知教师」 | PASS | PASS | 🔴 | PASS |
| 周次截断：`totalWeeks` 改成 `maxWeek`（不 clamp 到 30） | PASS | PASS | PASS | 🔴 |
| 开学日：`currentWeek` 强制置 0（不再按「第N周」反推） | 🔴 | 🔴 | 🔴 | 🔴 |

`skips` 对「位图基准」两条不敏感是**预期**的：它只用来测「跳过与截断」，周次数值没被钉死
（`weeks-bitmap` 与 `basic` 覆盖了基准）。其余每一条改坏都至少让一条用例变红。

## 八、`warnings` 实测（条数与长度）

`warn()` 的硬上限是 20 条 / 每条 ≤200 字（`MAX_WARNINGS`），实测跑满的用例里：

- `computed-index`：7 条，最长 66 字；
- `skips`：6 条，最长 51 字；
- `basic`：3 条，最长 66 字；
- `weeks-bitmap`：5 条，最长 66 字。

最长的一条是「周次位图第 0 位为 1……」（66 字），离 200 字上限还有很大余量。

## 九、已知边界（诚实写）

1. **fixture 是合成的，不是真实抓取**（维护者手上没有可公开的真实课表页）。形状取自上游
   `hunnu.js` 的正则与同平台 `UESTC/uestc.js` 的注释（`TaskActivity` 第 1 个参数是教师、
   第 3 个是课名、第 5 个是教室、第 6 个是位图），课程名 / 教师 / 教室全是虚构。
   **代价**：只保证「同样的输入永远得到同样的输出」，挡不住「教务的真实写法与我们猜的形状不同」。
   拿到真实 dump 请替换 `fixtures/*.extracted.json`。
2. **教师按 `actTeachers` 读，不用 `args[1]`**：本件 `args[1]` 写的也是同一个字面量名字，
   但同平台有脚本把它写成 `join` 表达式，所以按上游 HUNNU 的读法取 `actTeachers`。
   一个块里 `actTeachers` 有多个老师时**只取第一个**（上游的正则也只抓第一个 name）；
   真机若发现教师显示不全，这里是要看的一处。
3. **课名截到第一个半角括号之前**（与上游一致，「高等数学A(一)」→「高等数学A」），
   课名以半角括号开头时不截。全角括号（「体育（一）」）保留。
4. **同一门课的相邻节次不做合并**：上游会把第 1、2 节拼成「1-2 节」并合并周次，
   这会丢「两节周次不同」的信息；本件保持「一个节次一条 block」，应用自己会画成连堂。
5. **`index` 是已算好的裸数字时**（`index = 62`）按 `unitCount` 反推，
   而 `unitCount` 若又是内置的 13（页面没读到），这条会算错星期/节次 —— 两件事
   各自都有 warnings，但不保证用户能从预览里对上。
6. **只发课表里用到的节次的作息时间**（不是整张 13 节表）：载荷校验要求
   `startWeek/endWeek ∈ 1..totalWeeks`，但与节次表长度无关；少发的节次在课表里没有上下课时间。
7. **没有真机验证**（无账号）：`loginUrl` 与 iframe 选择器照上游抄，未在真实教务上跑过。
8. **`<a>` 点击在轮询等待期间只等 iframe 出现**，不判断点下去是否真的生效；点不动
   （选择器变了）时 `frameFound` 为 false，parse 会按「没读到 TaskActivity」报错，
   用户在 warnings/报错里能看到「请先点开我的课表」。

## 十、签名

移植者：**0x7E-2023**
日期：**2026-09-17**
本审计的每一条声明都与 `extract.js` / `parse.js` 的实际代码一致；上面所有 grep 命令与输出
都是在本仓库工作区里实跑得到的。
