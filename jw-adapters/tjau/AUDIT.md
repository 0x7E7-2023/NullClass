# 安全审计 —— 天津农学院（树维 EAMS 平台）

审计对象：`jw-adapters/tjau/` 下的 `extract.js` / `parse.js`，以及它们移植自的上游脚本
`shiguang_warehouse` 的 `TJAU/tjau.js`（快照 commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，
2026-09-12，MIT，上游 `resources/TJAU/adapters.yaml` 的 maintainer 是 `星河欲转`）。

- 移植者：`0x7E7-2023`
- 移植日期：2026-09-16（批次四，树维 EAMS 整族）
- 适配器 key：`tjau`
- 上游标注：`adapter_name: 天津农学院树维教务`，`import_url: http://jwxt.tjau.edu.cn/eams/homeExt.action`

审计方式：逐行读上游脚本 + 逐行读移植件，并跑移植手册 §5 给的静态扫描
（`grep -oE "https?://[A-Za-z0-9.-]+"` 与
`grep -nE "fetch\(|XMLHttpRequest|sendBeacon|new WebSocket|\.src\s*=|localStorage|sessionStorage|password|eval\(|new Function|Function\("`）。

上游扫描结果：绝对 URL 只有 **一个** `http://jwxt.tjau.edu.cn`（出现 3 次，三条请求都把它写死在地址里）；
`fetch` 只有一处（`request()` 包装）；`Function(` 一处（第 205 行，见 §2 第 6 条）。

---

## 1. 请求域与请求清单（本节必须与代码一致，不许写得比代码窄）

上游 `TJAU/tjau.js` 的三条请求都写成**绝对地址** `http://jwxt.tjau.edu.cn/eams/...`。
移植件**没有沿用**绝对地址，改成按当前页面的 origin + 上下文路径 `/eams` 拼
（`originOf()` + `eamsBase()`），所以两个脚本请求的主机只有**用户当前所在的那一个教务主机**
（正常就是 `loginUrl` 的主机 `jwxt.tjau.edu.cn`）。**`allowHosts` 留空，不写通配。**

`eamsBase()` 会在当前地址里找 `/eams/` 这一段并保留它之前的前缀 —— 万一学校把教务挂在门户 /
WebVPN 前缀下（`/webvpn/eams/...`），请求跟着用户实际打开的那个前缀走，脚本不替教务系统写死主机。

| 谁 | 方法 | 地址 | 干什么 | 取不到时 |
|---|---|---|---|---|
| extract.js | GET | `<origin><前缀>/eams/courseTableForStd.action?sf_request_type=ajax` | 读课表页 HTML 里的两个参数：学号 `ids`（`bg.form.addInput(form,"ids","…")`）与学期组件 id（`semesterBar…Semester`，再取它的 `value` 当当前学期 id）。**与上游同款正则、同一个接口** | 报错并提示重新登录（没有这两个参数就发不出课表请求） |
| extract.js | POST | `<origin><前缀>/eams/dataQuery.action?sf_request_type=ajax` | 取**学期日历**：`tagId=<学期组件 id>&dataType=semesterCalendar`。响应是 JS 对象字面量，里面有每个学期的 `id` / `name` / `schoolYear` / `startDate` / `endDate` 与当前学期 `semesterId`。**请求体与上游逐字一致** | 报错（这个接口挂了就没有学期，也就没有课表请求的 `semester.id`） |
| extract.js | POST | `<origin><前缀>/eams/courseTableForStd!courseTable.action?sf_request_type=ajax` | 取**课表 HTML**：`ignoreHead=1&setting.kind=std&semester.id=<选中的学期>&ids=<学号>`。**请求体与上游逐字一致**（上游不带 `startWeek=`，本件也不带） | 报错并提示重新登录 |

- 三条请求**全部来自上游** `TJAU/tjau.js`：接口、方法、菜单参数、请求体逐字对应，
  **没有新增任何接口**，也没有读课表页之外的模块。
- 请求头只带 `Content-Type` / `X-Requested-With` / `Accept`，**不带任何自定义令牌**；
  Cookie 由 WebView 自己按同源规则带上，脚本既不读它、也不把它发到别处。
- `parse.js` 不发任何请求（CI 里用 Rhino 实跑，是纯函数）。

**读到的数据里有什么、交出去什么**：

| 来源 | 读什么 | 交出去什么 |
|---|---|---|
| 当前页面 | `document.readyState`、`document.documentElement.innerHTML`（只为找页面自己的 `unitCount`） | 只交 `unitCountPage` 一个数字 |
| 课表页 HTML | 学号 `ids`、学期组件 id、组件元素上的学期 `value` | **一个都不交**（`ids` 只用来拼课表请求的 body；见 §3 第 8 条） |
| 学期日历响应 | 整段原文（对象字面量） | 原文交出去（它是**学校的公共校历**，学期名 + 起止日期，不含个人信息），另外交一个解析好的「选中学期」对象（id / 学期名 / 学年 / 起止日期 / 选中依据） |
| 课表响应 | 课表 HTML 全文 | 原文交出去（课程名、教师、教室、周次位图都在里面，这就是课表本身） |

不读的东西（静态扫描无命中）：`password` / `pwd` / 登录表单、`localStorage` / `sessionStorage`、
`document.cookie`、成绩、学籍、个人信息、缴费、任何其它模块的接口。

---

## 2. 移植手册 §5 八条逐条

| # | 检查项 | 结论 |
|---|---|---|
| 1 | 不碰凭据 | **过**。两个脚本都没有 `password` / `pwd` / 登录表单读取，也没有 `localStorage` / `sessionStorage` / `document.cookie` 访问。**上游那个 `showSingleSelection`（选学期）弹窗没有移植**：改成自动取当前学期（学期日历的当前学期 id → 起止日期包含今天的那一个 → 起始日期最晚的那一个），一个字段都不问用户要 |
| 2 | 不外发 | **过**。全部 `fetch` 都在 `extract.js` 里（共一处，被三条调用点复用），地址全部由 `window.location.origin` + 上下文路径 `/eams` 拼成；没有 `sendBeacon` / `WebSocket` / `EventSource` / `new Image().src` / 隐藏表单，也没有任何第三方域（脚本里唯一的绝对 URL 是文件头里写上游仓库的 `https://github.com`，不是请求） |
| 3 | 请求域可控 | **过**。请求主机只有一个：`loginUrl` 的主机 `jwxt.tjau.edu.cn`（`loginUrl` 给的是 http；`extract.js` 不在页面里跳转、只按当前 origin 拼地址，所以走 http 还是 https 由用户实际打开的那个地址决定）。`allowHosts` 留空，**不写通配**；代码里也没有写死主机名 |
| 4 | 只读课表 | **过**。三条请求全部是 `/eams/courseTableForStd.action`、`/eams/dataQuery.action`（`dataType=semesterCalendar`）、`/eams/courseTableForStd!courseTable.action` —— 树维 EAMS 的课表查询三连，**不碰成绩、学籍、个人信息、缴费**等任何其它接口。学号只用于拼课表请求的 body，不写进输出、不进 fixture |
| 5 | 不埋点 | **过**。没有任何统计 / 上报 / 遥测，也没有 `img` 打点 |
| 6 | 不 eval 远程代码 | **过，且这是本件改动最大的一条。** 上游第 205 行是 `Function("return (" + raw + ")")()` —— `raw` 是 `dataQuery.action` 的响应体，**来自网络**，这正好命中手册 §5 第 6 条「`eval` / `new Function` 里塞了从网络取回来的字符串」。移植件**没有照抄**：学期日历改成 `extract.js` 里那组 `splitTop` / `outerOf` / `objectOf` / `arrayOf` **只扫字面量、绝不求值**的函数（按顶层逗号与冒号切分，括号深度与引号状态感知），`parse.js` 里连一个 `Function(` 都没有。回应范围：这套解析只看 `semesters` / `semesterId` / `id` / `name` / `schoolYear` / `startDate` / `endDate` 这几个字段，响应里其它字段（含任何表达式或函数）都只是被当字符串扫过，不执行 |
| 7 | 不写页面 | **过**。`extract.js` 只**读** `document.documentElement.innerHTML`（为了找页面自己的 `unitCount`）与 `document.readyState`，既不写 DOM、不改表单、也不触发提交或点击。上游的 `showToast` / `notifyTaskCompletion` / `saveImportedCourses` / `savePresetTimeSlots` 这类桥调用**全部没有移植** |
| 8 | 不依赖用户输入之外的秘密 | **过**。没有硬编码密钥、令牌或他人的学号。脚本里的常量只有树维的上下文路径 `/eams`、响应长度上限与作息表；学号是**当场从已登录页面上读的**，只用于这一次请求 |

**结论：可以进内置库。** 三条请求全是本校教务的课表查询接口，只读课表，
不碰凭据、不外发、不埋点、不写页面，并且把上游唯一的动态求值（`Function(...)`）换成了纯字面量扫描。

---

## 3. 本批 12 条检查表逐条

| # | 检查项 | 本件结论 |
|---|---|---|
| 1 | **周次位图的下标基准** | **过，且有用例**。统一口径实现：`bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；`bitmap[0] === '1'` **不产出第 0 周**，改为计数 + 一条 `warnings`。上游原文 `for (let j = 0; j < weeksBitmap.length; j++) { if (weeksBitmap[j] === '1') weeks.push(j); }` **会把 0 位 push 进去**，于是「第 0 周」会进载荷，而 `JwSchedulePayload.validate` 要求 `startWeek >= 1` —— 整包会被拒。用例：`fixtures/weeks-bitmap` 里「线性代数」= 0 位为 1 且 1-16 位为 1（期望 1-16）、「军事理论」= 只有第 1 位为 1（期望只有第 1 周，证明基准不是 index+1）、「大学物理」= 0 位与 2-16 位为 1（期望 2-16）、「劳动教育」= 只有 0 位为 1（期望**不出现** + 一条说明）。变异见 §7 的 M1b / M1c |
| 2 | `TaskActivity` 的参数位 | **过，且本件的坑比计划里写的更深**。`args[3]` 课名、`args[5]` 教室、`args[6]` 位图一致。`args[1]`（教师）本件同族**本来就写的是 `teachers.join(",")` 表达式**（不是字面量）—— 上游的办法是另取同一个块里的 `var actTeachers = [...]` 的第一个 `name`，取不到才拿 `args[1]` 原值（那会把 `teachers.join(",")` 当成教师名）。移植件：`actTeachers` 取到就用；取不到时**只在 `args[1]` 是字面量时才用**，是表达式就留空 + 一条 `warnings`。为此 `powerSplit` 与上游有一处刻意差别：**返回未清洗的原始段**（上游在切分时就把引号剥掉，剥完之后「表达式」与「字面量」分不出来）。用例：`fixtures/edge-units` 的「数据库原理」（没有 `actTeachers`，教师必须是 `null`） |
| 3 | 两种 `index` 写法 | **过**。`index = 星期 * unitCount + 节次` 与已算好的 `index = 62` 都认，**换算不用 `eval` / `new Function`**（`Math.floor(linear / unitCount) + 1` / `(linear % unitCount) + 1`）。上游本件不认裸数字，本件按本批检查表补上（同族的 HPU 就是这么做的）。`unitCount` 读不到时裸数字**没法换算**：计数 + 一条 `warnings`，不猜。用例：`fixtures/edge-units`（无 `unitCount`，「计算机网络」的 `index = 0*unitCount+4` 与 `index = 62` 混在同一块里 → 两次定位都进课表） |
| 4 | `unitCount` 要真的读 | **过**。从课表响应里读 `unitCount = N`（先认上游那种带分号的写法，再放宽），读不到时用上游的缺省值 14，**并且一定进 `warnings`**（不静默用一个数字）。用例：`fixtures/edge-units` 的第一条 `warnings` 就是这条 |
| 5 | 作息时间从哪来 | **过**。本件是**脚本内置**那一路：上游 `TJAU/tjau.js` 的 `applyTimeSlots()` 里那张 **11 节**表，逐条原样搬过来（第 1 节 `08:30-09:15`）。逐条过 `HH:mm` 校验（`^([01]?\d\|2[0-3]):([0-5]\d)`，`24:00` 这种直接不匹配）与 `start < end`，越界的节次丢弃 + 计数 + `warnings`。课表用到第 12 节时按空课内建 `DefaultPeriodTimes` 补时间并单独 `warnings` |
| 6 | 开学日 | **过**。优先用学期日历里**选中学期**的 `startDate`，再按手册 §4.3 回退到那一周的周一（不是把 `startDate` 直接当 `firstDay`）；拿不到（学期日历没给日期 / 只有页面上的学期 id）就按「第一学期 9 月 1 日、第二学期 2 月 20 日所在周的周一」推算，**推算值一定出现在 `warnings` 里**，并且会说明是哪一种来源。上游的 `showSingleSelection` 问用户那条路**没有保留** |
| 7 | 周次上限 | **过**。位图超过第 30 位的周次丢弃 + 计数 + `warnings`；`totalWeeks` 先取学期起止日期算出的周数（回落到 20），再被课表里更晚的周次顶高（单独 `warnings`），最后 clamp 到 30（再单独 `warnings`），三层都不静默 |
| 8 | `teacher` / `location` 拿不到就留空 | **过**。教师与教室读不到就是 `null`（不再写「未知教师」「未知地点」）。**另外**：上游对教室做 `replace(/\(.*?\)/g, "")`，会把「综合楼B101(东)」砍成「综合楼B101」—— 本件只剥成对的引号、括号内容原样保留（用例：`fixtures/edge-units` 的「数据库原理」） |
| 9 | 学期名 | **过**。用学期日历给的 `schoolYear` + `name`（`1` / `2`）拼成「2026-2027学年第一学期」，`name` 里已经带「学期」两个字时直接用原文；都没有才回落「天津农学院 + 学年」。**没有拿适配器名当学期名**，也没有写死学校名当学期名 |
| 10 | `allowHosts` | **过**。`allowHosts: []` —— 三条请求全是**当前页同源相对路径**，只放行 `loginUrl` 那一个主机，没有任何通配。本件**不使用 OCR / 提问桥**（源码里没有 `__ncOcr` / `__ncOcrGrid` / `__ncSelect` 等全局名），所以不存在「通配拿不到桥」的问题 |
| 11 | `warnings` 上限 | **过**。所有 `warnings` 过一个 `warn()` 收口：单条超 200 字先截断（补「…」），总数超过 20 条不再追加并如实记数（正常路径最多 6 条）。本地自验脚本逐条复核了 4 份期望载荷的条数与每条长度 |
| 12 | 每件都要做变异测试 | **已做，见 §7** |

---

## 4. 三处必须专门写清楚的决定

### 4.1 位图第 0 位：上游没跳，我们跳了

**上游原文**（`TJAU/tjau.js` 第 155-158 行）：

```js
const weeks = [];
for (let j = 0; j < weeksBitmap.length; j++) {
    if (weeksBitmap[j] === '1') weeks.push(j);
}
```

`j` 从 0 起，所以**位图第 0 位是 1 时它会 push 一个 0**，也就是「第 0 周」。

**本件实现**：`bitmap[i] === '1'` 且 `i >= 1` → 第 `i` 周；`i === 0` 为 1 时**不产出周次**，
只计数并写一条 `warnings`（「周次位图第 0 位是 1，同族约定里这一位是占位符，不是第 0 周，已按忽略处理」）。

**依据**（两条，独立的）：

1. **载荷校验会拒**：`JwSchedulePayload.validate` 要求 `block.startWeek >= 1`，
   「第 0 周」不是「少一周」而是**整包被拒**（用户什么都导不进来）。所以就算真值未知，也不能产出 0。
2. **同族五件的共同声明**：本批 12 件里 `uestc`（「position 0 始终是 0，忽略」）、
   `hpu`（`if (text[i] === "1" && i >= 1)`）、`hunnu`（`for (let i = 1; ...)`）、
   `zua` / `zzvcae`（`for (week = 1; ...)`）**五件在代码或注释里明确写下同一个约定**：
   「位图下标 i 就是第 i 周，下标 0 是占位符」。本批统一按这个口径实现（批次文档 §每件的实测事实）。
   上游 `hfnu` / `xatu` / `tjau` 三件是同一段循环，只是都没跳 0 位 —— 它们**不代表另一种编码**，
   而是同一个疏漏被克隆了三次。

**风险与回退**：真机上如果发现整体差一周（例如位图其实是从 0 位=第 1 周），
改 `weeksOfBitmap` 里那三行即可（把 `i` 换成 `i + 1`），用例会立刻变红（§7 的 M1b 就是这条路）。
**没有账号，无法验证真值** —— 这一条写在这里就是为了让下一个拿到账号的人一眼看到改哪里。

### 4.2 本件是 `xatu` / `hfnu` 克隆链的源头

上游 XATU/myschool.js 的文件头自己写着「**基于天津农学院适配脚本**」，HFNU/hfnu.js 与它逐字同构。
核对结果：三件的 `powerSplit` / `cleanArg` / `mergeContinuousLessons` / `parseTaskActivities` /
位图循环 / `idxRegex` **逐字相同**，差异只在主机、作息表、提示语，以及 XATU 额外加的一个
`isTeachingBuilding3` 标记。**也就是说 `tjau` 是源头，另两件是它的克隆。**

这条带来的风险是「照抄邻件的期望值」，本件的处理：

1. **作息表按 tjau 自己的来**：tjau 是 `08:30` 起、11 节（上午 4 节）；
   `hfnu`（11 节但两套校区作息）与 `xatu` 的表里第 1 节是 `08:00`。
   本件 `SCHOOL_PERIOD_TIMES` 逐条抄的是上游 **TJAU** 的 `applyTimeSlots()`，
   4 份期望载荷里的 `periodTimes` 全部是 `08:30-09:15` 起 —— 抄邻件会在这里立刻露馅。
2. **主机按 tjau 自己的来**：`loginUrl` 与 `scheduleUrlHint` 都是 `jwxt.tjau.edu.cn`，
   上游给的是 `http`（会在应用里显示「不安全连接」标记）；请求路径按同源的 `/eams/...` 写，
   三件的主机不同（`jwgl2018.xatu.edu.cn` / `jw.hfnu.edu.cn`），本件没有沿用任何绝对地址。
3. **不抄邻件的缺陷**：见 §4.3 与 §3 第 2 条 —— 课程名括号、`未知教师` / `未知地点`、
   教室括号被 `replace` 砍掉，这三处同族三件都有，本件一处都没有抄。
4. **不抄邻件的期望值**：4 份 `*.expected.json` 都是按本件代码的语义**手写**的
   （先按规范推周期区间，再写进期望），不是把 `parse.js` 的输出贴进去；
   §7 的变异测试证明它们盖的是真实路径。

### 4.3 课程名里的括号：保留，只摘纯数字序号

**上游原文**：`const courseName = (args[3] || "未知课程").split('(')[0];`
—— 取第一个左括号之前的全部内容。后果：「高等数学A(一)」→「高等数学A」、
「形势与政策（四）」**不变**（中文括号不是 ASCII 的 `(`）、「C语言程序设计(2)上机」→「C语言程序设计」。
**前两个都是丢信息**：`(一)` 是课名的组成部分（同一门课的第一学期/第二学期靠它区分），
砍掉之后「高等数学A(一)」和「高等数学A(二)」在课表里会变成同一个名字。

**本件实现**：`courseNameOf()` 只摘**末尾**「整段括号里全是数字」的序号后缀 ——
`/[(（]\s*\d{1,3}\s*[)）]\s*$/`，只在摘完还剩内容时才摘，并计数 + 一条 `warnings`。

| 输入 | 上游 | 本件 | 理由 |
|---|---|---|---|
| `高等数学A(一)` | `高等数学A` | `高等数学A(一)` | 括号里不是数字 → 保留 |
| `形势与政策（四）` | `形势与政策（四）`（中文括号没被切到，纯属侥幸） | `形势与政策（四）` | 同上，且不再依赖「括号是不是 ASCII」这种巧合 |
| `大学物理(2)` | `大学物理` | `大学物理` | 末尾括号里只有数字 = 教学班/课程序号，同一门课的多个教学班不该在课表里显示成两门课 |
| `C语言程序设计(2)上机` | `C语言程序设计` | `C语言程序设计(2)上机` | 括号不在末尾、后面还有内容 → 是课名的一部分 |
| `大学物理（二）(2)` | `大学物理` | `大学物理（二）` | 只摘末尾那一个纯数字括号 |

**依据**：「纯数字后缀 = 课程序号」这条口径不是本件自创 —— 同族 `NEUQ/neuq.js` 的
`cleanCourseName()` 就是 `replace(/\([\d.]+\)\s*$/, "")`（只摘**末尾**、只摘数字），
`UESTC/uestc.js` 摘的是「字母+数字.数字」形态的课程代码后缀，`HPU` 也是同类做法。
上游 `tjau` / `xatu` / `hfnu` 三件的 `split('(')[0]` 是这三件**自己的**写法，同族里只有它们这样。
**取舍**：宁可留下一个多余的括号，也不要静默砍掉课名的一部分 —— 留下用户看得见（导入预览里能核对），
砍掉用户看不见。`fixtures/course-name` 把这个决定钉死（§7 的 M2 证明它真的在看这条路径）。

---

## 5. 与同平台已移植件的对照（「同族 ≠ 同编码」）

本仓此前**没有任何树维 EAMS 适配器**（`masu` 的注释虽然提过 `/eams/`，但那是批次四才订正过来的
厂商名，且它走的是读 DOM 表格那条路，与本件的接口三连不是一套）。所以本件没有可对照的既有件，
对照的是**同批 12 件的上游脚本**：

| 层 | 本批能不能复用 | 本件结论 |
|---|---|---|
| 接口三连（`courseTableForStd.action` → `dataQuery.action` → `courseTableForStd!courseTable.action`） | **能** | 本件与批次文档里 ① 组 10 件同款；本件请求体照 `TJAU/tjau.js` 自己写的（**不带** `startWeek=`，而同族 `zua`/`zzvcae`/`hpu`/`neuq` 都带） |
| `TaskActivity` 内嵌格式（`args[3]` 课名 / `args[5]` 教室 / `args[6]` 位图） | **能** | 一致 |
| `args[1]` 教师的两副面孔 | **要小心** | 本件（同族三件）**是表达式那一路**：`teachers.join(",")`，真名在块内的 `actTeachers` 里。直接取 `args[1]` 会把表达式当教师名（上游就这么写的兜底） |
| 位图基准 | **不能复用** | 12 件有五种读法（批次文档 §每件的实测事实）；本件上游属于「会产出第 0 周」那一类，按统一口径收敛 |
| 作息表 | **不能复用** | 每校一张：本件 `08:30` 起 11 节（上游 TJAU 内置表），与 `xatu`/`hfnu` 的 `08:00` 不同 |

---

## 6. fixture 的合成来源与代价

4 份 `fixtures/*.extracted.json` 全部是**合成的**，文件头都有 `_note` 声明。
合成依据是上游 `TJAU/tjau.js` 实际读取的字段形状：

- `semester`：`dataQuery.action`（`dataType=semesterCalendar`）里选中学期的形状（本件 extract 的输出）；
- `semesterCalendar`：那个接口的响应**原文**形状（JS 对象字面量，不是 JSON；
  `{semesters:{"1":[…],"2":[…]},semesterId:454}`，每个学期有 `id` / `name` / `schoolYear` /
  `startDate` / `endDate`）—— 形状取自上游对 `data.semesters[key]` 与 `s.schoolYear` / `s.name` 的读法，
  以及同族 `zzvcae` 对 `startDate` / `endDate` 字段名的读法；
- `courseTable`：`courseTableForStd!courseTable.action` 的响应形状 —— `var unitCount = N;` +
  若干 `var teachers = [...]` 块，每块里 `var actTeachers = [...]`、一条 `new TaskActivity(...)`、
  若干 `index = 星期*unitCount+节次;`。形状取自上游 `parseTaskActivities` 的四个正则。

**代价**（与内置 `universal` 同一个档位，测试方案 §3）：只保证「同样的输入永远得到同样的输出」，
**不保证解析在真实页面上是对的**。课程、教师、教室都是虚构的，不含任何学号姓名等真实身份信息。
谁拿到真实 dump，替换 fixture 并重跑门是最高优先级的贡献。

---

## 7. 变异测试记录（本批检查表 12）

变异做法：把 `parse.js` **读进内存改副本**（写进 `%TEMP%`，**不碰工作区文件**），
在 `vm` 里跑同一套 fixture 比对。每次变异前先确认原始文件 4/4 `MATCH`。

| 变异 | 改坏了什么 | 结果 | 说明 |
|---|---|---|---|
| **M1b** | 位图基准整体偏一周：`weeks.push(i)` → `weeks.push(i + 1)` | **4/4 用例全红**：`basic` 14 处周次差异（`1-14` → `2-15`）、`weeks-bitmap`（`1-1` → `2-2`、`2-16` → `3-17`）、`course-name`、`edge-units` 同样全错 | 证明「下标即周次」这条基准真的被 4 份用例盯着；抄成 `i + 1`（上游本件没有、同族 `cuit` 有）会立刻红 |
| **M1c** | 0 位不再跳过（回到上游 `for (j = 0; …)` 的语义，只把 `i === 0` 的 `continue` 去掉，仍然计数） | **只有 `weeks-bitmap` 变红**（「劳动教育」从「跳过 + 一条说明」变成导进来一个 `startWeek: 0` 的 block），其余 3 件照旧绿 | 这正是「用例盖住了旧用例盖不住的那条路径」：另外三份 fixture 的位图第 0 位都是 `0`，**盖不到这一位**。注意这一条在 CI 里的表现会更狠 —— `startWeek: 0` 会让 `JwSchedulePayload.validate` **整包拒收**（不只这一门课） |
| **M2** | 课程名按上游那样砍：`name.replace(/末尾纯数字括号/, '')` → `name.split('(')[0]` | **`basic` 与 `course-name` 变红**（`大学英语(一)` → `大学英语`、`高等数学A(一)` → `高等数学A`、`C语言程序设计(2)上机` → `C语言程序设计`），`weeks-bitmap` 与 `edge-units` 照旧绿 | 证明 `fixtures/course-name` 与 `basic` 真的在看「括号保留」这条路径；`weeks-bitmap` / `edge-units` 里没有带括号的课名，盖不到 |
| **M3** | `unitCount` 读不到时不再进 `warnings`（静默用缺省 14） | **只有 `edge-units` 变红**（少一条 `warnings`，后续条目整体前移） | 见检查表 4：猜的值必须出声 |
| **M4** | 裸数字定位在 `unitCount` 读不到时不再出声（`bareWithoutUnit` 那条 `warnings` 去掉） | **只有 `edge-units` 变红** | 检查表 3 的「不许静默丢课」：丢的是「操作系统」那一门，必须说出来 |
| **M5** | 教师不再区分字面量与表达式：按上游同族的兜底写法把 `args[1]` 原值当教师名 | **只有 `edge-units` 变红**（「数据库原理」的 `teacher` 从 `null` 变成 `teachers.join(",")`） | 证明「表达式不当名字」这条真的有用例盯着；另外三份 fixture 的教师块都有 `actTeachers`，走不到这条兜底 |
| **M6** | `actTeachers` 取不到时也不再退回字面量（教师一律留空） | **只有 `edge-units` 变红**（「计算机网络」的 `teacher` 从 `许娟` 变成 `null`） | 与 M5 是一对：两个方向都被同一份用例钉住（该留空的留空、该用字面量的用字面量） |

**M1b / M1c / M2 / M3 / M4 / M5 / M6 各自红在不同的用例上**（M1b 全红，M1c 只红 `weeks-bitmap`，
M2 红 `basic` + `course-name`，M3–M6 都只红 `edge-units` 的不同条目），
说明四份用例覆盖的是**七条不同的路径**，不是同一件事的四种写法；也说明
`edge-units` 这一对边界用例单独盖了四条路径（`unitCount` 缺失、裸数字定位、表达式教师、字面量教师）。

原始文件在每次变异前后都是 4/4 `MATCH`，变异只作用于内存副本，**工作区文件没有被改动过** ——
本次会话末的哈希：`parse.js` = `af8bd90d9e458269be179c5e6aad6787da3beddc970d5f5c9dab0b241ac10cf8`、
`extract.js` = `ae5ade4db340803fa4bdea6033ac2467d3292eb998d86254149d455e208c0b8c`（sha256）。

本地自验脚本除了逐字段比对，还按 `JwPayloadCodec.validate` 的口径复核了 4 份**实际输出**的载荷合法性
（`specVersion=1`、`kind=schedule`、`totalWeeks ∈ 1..30`、`firstDay` 是 ISO、`periodTimes` 是
`HH:mm` 且 `start < end`、`dayOfWeek ∈ 1..7`、`startWeek ≥ 1`、`endWeek ≤ totalWeeks`、
`weekType ∈ {ALL,ODD,EVEN}`、`warnings ≤ 20` 条且每条 ≤ 200 字）：

```
VALID basic（载荷过校验：warnings 5 条，最长 83 字）
VALID weeks-bitmap（载荷过校验：warnings 6 条，最长 83 字）
VALID course-name（载荷过校验：warnings 6 条，最长 83 字）
VALID edge-units（载荷过校验：warnings 8 条，最长 83 字）
```

（CI 里跑的是**真版本**的 `JwPayloadCodec.decode` + `JwScheduleNormalizer.normalize` —— 见测试方案 §2 第 4 条；
本地这一遍只是提前把明显的越界挡住，不代表真门已经跑过。）

---

## 8. 已知边界与没做的事（诚实写）

1. **没有账号，未做真机验证**。三条接口的路径、参数、请求体都照上游逐字搬，但「tjau 的
   `dataQuery.action` 响应里真有 `startDate` / `endDate` 字段」这一点是从**同族 `zzvcae` 的读法**
   推的（上游 `TJAU/tjau.js` 自己**只读 `id` / `name` / `schoolYear`**，把日期扔了）。
   如果该校的响应里没有这两个字段，本件会走「按 9 月 1 日推算」那条路，并在 `warnings` 里说清楚 ——
   不会静默写一个错日期。
2. **作息时间是脚本内置值，不是从教务读的**。`SCHOOL_PERIOD_TIMES` 是上游
   `TJAU/tjau.js` 的 `applyTimeSlots()` 里那张 11 节表（第 1 节 `08:30-09:15`），
   本件逐条搬过来。**真机核对时请对照教务处公布的作息**：上游这张表有可能过时，
   而它决定课表里每一节显示的时间。载荷里带了 `periodTimes` 时应用不会自己补默认表，
   所以这张表是「用了什么就是什么」。
3. **位图基准的真值未验证**（§4.1）。统一口径是按同族五件的声明定的，本件自己的上游没声明；
   真机上若发现整体差一周，改 `weeksOfBitmap` 一处即可。
4. **`unitCount` 读不到 + 只有裸数字定位的课会丢**（`fixtures/edge-units` 的「操作系统」）。
   这是**有意的**：没有 `unitCount` 就无法把线性下标换算成星期与节次，猜一个数字
   （例如沿用缺省 14）会把整门课放到错误的星期上，比丢掉更糟；丢掉会出一条 `warnings`。
5. **学期日历的 `semesters` 分组键**（`"1"` / `"2"`）本件不解释，只把所有分组里的学期**平铺**成
   一个列表再挑（上游也是平铺的，`for (let key in data.semesters)`）。`name` 字段被当作
   「第几学期」用（`"1"` → 第一学期），这是上游同族一致的做法。
6. **`parse.js` 从 `semesterCalendar` 原文里再扫一遍**（`semestersOf`），
   `extract.js` 其实已经把选中学期解析好了。这是有意的冗余：`parse.js` 是**唯一在 CI 里跑得动**
   的那一段，它自己能从原始响应恢复出学期信息，才谈得上「回归」。两个解析器读的是同一组字段名。
7. **`extract.js` 在 CI 里一次都不会执行**（测试方案 §2）—— 它的一切（三条接口、正则、
   同源拼接）只靠人工审计与用户反馈。所以它写得尽量薄：不解析课程、不算周次、只取数。

---

## 9. 签名

- 上游出处：`shiguang_warehouse` → `resources/TJAU/tjau.js`
  （commit `e62554a4034386b893bcd6813c7b2b64f8c730a3`，2026-09-12，MIT）
- 上游作者 / maintainer：`星河欲转`（`resources/TJAU/adapters.yaml`）
- 移植者：`0x7E7-2023`
- 移植日期：2026-09-16
- 本件的安全结论：**可以进内置库**（§2 八条全过、§3 十二条逐条落实）
- 本件的开放风险：位图基准（§4.1）与作息时间（§8 第 2 条）**没有真机依据**，
  两处都在导入预览的 `warnings` 里有对应提示

**自验与变异测试记录（同一次会话内的输出，原样摘录）**：

```
$ node check.js                      # 读 fixtures/*.extracted.json 当 __ncInput，vm 里跑 parse.js
MATCH basic
MATCH weeks-bitmap
MATCH course-name
MATCH edge-units

$ node check.js m1b.js               # 变异：weeks.push(i) → weeks.push(i + 1)
FAIL basic   （14 处周次差异，例如 .terms[0].courses[0].blocks[0].startWeek 期望 1 / 实际 2）
FAIL weeks-bitmap
FAIL course-name
FAIL edge-units

$ node check.js m1c.js               # 变异：0 位不再跳过（上游语义）
MATCH basic
FAIL weeks-bitmap   （「劳动教育」不再被跳过）
MATCH course-name
MATCH edge-units

$ node check.js m2.js                # 变异：课程名改成上游的 split('(')[0]
FAIL basic          （高等数学A(一) → 高等数学A）
MATCH weeks-bitmap
FAIL course-name    （C语言程序设计(2)上机 → C语言程序设计）
MATCH edge-units

$ node check.js m3.js                # 变异：unitCount 读不到不再 warn
MATCH basic
MATCH weeks-bitmap
MATCH course-name
FAIL edge-units      （少一条 warnings）

$ node check.js m4.js                # 变异：裸数字定位无法换算时不再出声
MATCH basic / MATCH weeks-bitmap / MATCH course-name
FAIL edge-units      （少一条 warnings）

$ node check.js m5.js                # 变异：按上游同族兜底，把 args[1] 表达式当教师名
MATCH basic / MATCH weeks-bitmap / MATCH course-name
FAIL edge-units      （数据库原理的 teacher 从 null 变成 teachers.join(",")）

$ node check.js m6.js                # 变异：actTeachers 取不到时也不退回字面量
MATCH basic / MATCH weeks-bitmap / MATCH course-name
FAIL edge-units      （计算机网络的 teacher 从 许娟 变成 null）

$ node check.js                      # 还原后再跑一次
MATCH basic / MATCH weeks-bitmap / MATCH course-name / MATCH edge-units
```
